package com.sequenceiq.cloudbreak.controller;

import static com.sequenceiq.cloudbreak.cloud.model.Platform.platform;
import static com.sequenceiq.cloudbreak.util.SqlUtil.getProperSqlErrorMessage;

import java.util.Optional;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.convert.ConversionService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.sequenceiq.cloudbreak.api.model.stack.StackRequest;
import com.sequenceiq.cloudbreak.api.model.stack.StackResponse;
import com.sequenceiq.cloudbreak.api.model.stack.StackValidationRequest;
import com.sequenceiq.cloudbreak.api.model.stack.cluster.ClusterRequest;
import com.sequenceiq.cloudbreak.api.model.stack.instance.InstanceStatus;
import com.sequenceiq.cloudbreak.cloud.model.CloudCredential;
import com.sequenceiq.cloudbreak.cloud.model.StackInputs;
import com.sequenceiq.cloudbreak.common.model.user.IdentityUser;
import com.sequenceiq.cloudbreak.common.type.APIResourceType;
import com.sequenceiq.cloudbreak.controller.exception.BadRequestException;
import com.sequenceiq.cloudbreak.controller.validation.StackSensitiveDataPropagator;
import com.sequenceiq.cloudbreak.controller.validation.ValidationResult;
import com.sequenceiq.cloudbreak.controller.validation.ValidationResult.State;
import com.sequenceiq.cloudbreak.controller.validation.Validator;
import com.sequenceiq.cloudbreak.controller.validation.filesystem.FileSystemValidator;
import com.sequenceiq.cloudbreak.controller.validation.template.TemplateValidator;
import com.sequenceiq.cloudbreak.converter.spi.CredentialToCloudCredentialConverter;
import com.sequenceiq.cloudbreak.core.CloudbreakImageCatalogException;
import com.sequenceiq.cloudbreak.core.CloudbreakImageNotFoundException;
import com.sequenceiq.cloudbreak.core.flow2.service.ReactorFlowManager;
import com.sequenceiq.cloudbreak.domain.Blueprint;
import com.sequenceiq.cloudbreak.domain.stack.Stack;
import com.sequenceiq.cloudbreak.domain.stack.StackValidation;
import com.sequenceiq.cloudbreak.domain.stack.cluster.Cluster;
import com.sequenceiq.cloudbreak.domain.stack.instance.InstanceGroup;
import com.sequenceiq.cloudbreak.domain.stack.instance.InstanceMetaData;
import com.sequenceiq.cloudbreak.logger.MDCBuilder;
import com.sequenceiq.cloudbreak.service.AuthenticatedUserService;
import com.sequenceiq.cloudbreak.service.ClusterCreationSetupService;
import com.sequenceiq.cloudbreak.service.StackUnderOperationService;
import com.sequenceiq.cloudbreak.service.TransactionService;
import com.sequenceiq.cloudbreak.service.TransactionService.TransactionExecutionException;
import com.sequenceiq.cloudbreak.service.TransactionService.TransactionRuntimeExecutionException;
import com.sequenceiq.cloudbreak.service.account.AccountPreferencesValidationException;
import com.sequenceiq.cloudbreak.service.account.AccountPreferencesValidator;
import com.sequenceiq.cloudbreak.service.decorator.StackDecorator;
import com.sequenceiq.cloudbreak.service.image.ImageService;
import com.sequenceiq.cloudbreak.service.image.StatedImage;
import com.sequenceiq.cloudbreak.service.sharedservice.SharedServiceConfigProvider;
import com.sequenceiq.cloudbreak.service.stack.StackService;

@Service
public class StackCreatorService {

    private static final Logger LOGGER = LoggerFactory.getLogger(StackCreatorService.class);

    @Inject
    private StackDecorator stackDecorator;

    @Inject
    private FileSystemValidator fileSystemValidator;

    @Inject
    private AuthenticatedUserService authenticatedUserService;

    @Inject
    private CredentialToCloudCredentialConverter credentialToCloudCredentialConverter;

    @Inject
    private StackSensitiveDataPropagator stackSensitiveDataPropagator;

    @Inject
    private ClusterCreationSetupService clusterCreationService;

    @Inject
    private StackService stackService;

    @Inject
    private ReactorFlowManager flowManager;

    @Inject
    private ImageService imageService;

    @Inject
    @Qualifier("conversionService")
    private ConversionService conversionService;

    @Inject
    private AccountPreferencesValidator accountPreferencesValidator;

    @Inject
    private TemplateValidator templateValidator;

    @Inject
    private SharedServiceConfigProvider sharedServiceConfigProvider;

    @Inject
    private Validator<StackRequest> stackRequestValidator;

    @Inject
    private TransactionService transactionService;

    @Inject
    private StackUnderOperationService stackUnderOperationService;

    public StackResponse createStack(IdentityUser user, StackRequest stackRequest, boolean publicInAccount) {
        ValidationResult validationResult = stackRequestValidator.validate(stackRequest);
        if (validationResult.getState() == State.ERROR) {
            LOGGER.info("Stack request has validation error(s): {}.", validationResult.getFormattedErrors());
            throw new BadRequestException(validationResult.getFormattedErrors());
        }

        stackRequest.setAccount(user.getAccount());
        stackRequest.setOwner(user.getUserId());
        stackRequest.setOwnerEmail(user.getUsername());

        Stack savedStack;
        try {
            savedStack = transactionService.required(() -> {
                long start = System.currentTimeMillis();
                Stack stack = conversionService.convert(stackRequest, Stack.class);
                String stackName = stack.getName();
                LOGGER.info("Stack request converted to stack in {} ms for stack {}", System.currentTimeMillis() - start, stackName);

                MDCBuilder.buildMdcContext(stack);

                start = System.currentTimeMillis();
                stack = stackSensitiveDataPropagator.propagate(stackRequest.getCredentialSource(), stack, user);
                LOGGER.info("Stack propagated with sensitive data in {} ms for stack {}", System.currentTimeMillis() - start, stackName);

                start = System.currentTimeMillis();
                stack = stackDecorator.decorate(stack, stackRequest, user);
                LOGGER.info("Stack object has been decorated in {} ms for stack {}", System.currentTimeMillis() - start, stackName);

                stack.setPublicInAccount(publicInAccount);

                start = System.currentTimeMillis();
                validateAccountPreferences(stack, user);
                LOGGER.info("Account preferences has been validated in {} ms for stack {}", System.currentTimeMillis() - start, stackName);

                if (stack.getOrchestrator() != null && stack.getOrchestrator().getApiEndpoint() != null) {
                    stackService.validateOrchestrator(stack.getOrchestrator());
                }

                for (InstanceGroup instanceGroup : stack.getInstanceGroups()) {
                    templateValidator.validateTemplateRequest(stack.getCredential(), instanceGroup.getTemplate(), stack.getRegion(),
                            stack.getAvailabilityZone(), stack.getPlatformVariant());
                }

                Blueprint blueprint = null;
                if (stackRequest.getClusterRequest() != null) {
                    start = System.currentTimeMillis();
                    StackValidationRequest stackValidationRequest = conversionService.convert(stackRequest, StackValidationRequest.class);
                    LOGGER.info("Stack validation request has been created in {} ms for stack {}", System.currentTimeMillis() - start, stackName);

                    start = System.currentTimeMillis();
                    StackValidation stackValidation = conversionService.convert(stackValidationRequest, StackValidation.class);
                    LOGGER.info("Stack validation object has been created in {} ms for stack {}", System.currentTimeMillis() - start, stackName);

                    blueprint = stackValidation.getBlueprint();

                    start = System.currentTimeMillis();
                    stackService.validateStack(stackValidation, stackRequest.getClusterRequest().getValidateBlueprint());
                    LOGGER.info("Stack has been validated in {} ms for stack {}", System.currentTimeMillis() - start, stackName);

                    CloudCredential cloudCredential = credentialToCloudCredentialConverter.convert(stack.getCredential());

                    start = System.currentTimeMillis();
                    fileSystemValidator.validateFileSystem(stackValidationRequest.getPlatform(), cloudCredential, stackValidationRequest.getFileSystem());
                    LOGGER.info("Filesystem has been validated in {} ms for stack {}", System.currentTimeMillis() - start, stackName);

                    start = System.currentTimeMillis();
                    clusterCreationService.validate(stackRequest.getClusterRequest(), cloudCredential, stack, user);
                    LOGGER.info("Cluster has been validated in {} ms for stack {}", System.currentTimeMillis() - start, stackName);
                }

                String platformString = platform(stack.cloudPlatform()).value().toLowerCase();
                StatedImage imgFromCatalog;
                try {
                    imgFromCatalog = imageService.determineImageFromCatalog(stackRequest.getImageId(), platformString,
                            stackRequest.getImageCatalog(), blueprint, shouldUseBaseImage(stackRequest.getClusterRequest()), stackRequest.getOs());
                } catch (CloudbreakImageNotFoundException | CloudbreakImageCatalogException e) {
                    throw new TransactionExecutionException(e);
                }

                fillInstanceMetadata(stack);
                start = System.currentTimeMillis();

                stack = stackService.create(user, stack, platformString, imgFromCatalog);
                LOGGER.info("Stack object and its dependencies has been created in {} ms for stack {}", System.currentTimeMillis() - start, stackName);

                try {
                    createClusterIfNeed(user, stackRequest, stack, stackName, blueprint);
                } catch (Exception e) {
                    throw new TransactionExecutionException(e);
                }
                prepareSharedServiceIfNeed(stackRequest, stack, stackName);
                return stack;
            });
        } catch (TransactionExecutionException e) {
            stackUnderOperationService.off();
            if (e.getCause() instanceof DataIntegrityViolationException) {
                String msg = String.format("Error with resource [%s], error: [%s]",
                        APIResourceType.STACK, getProperSqlErrorMessage((DataIntegrityViolationException) e.getCause()));
                throw new BadRequestException(msg);
            }
            throw new TransactionRuntimeExecutionException(e);
        }

        long start = System.currentTimeMillis();
        StackResponse response = conversionService.convert(savedStack, StackResponse.class);
        LOGGER.info("Stack response has been created in {} ms for stack {}", System.currentTimeMillis() - start, savedStack.getName());

        start = System.currentTimeMillis();
        flowManager.triggerProvisioning(savedStack.getId());
        LOGGER.info("Stack provision triggered in {} ms for stack {}", System.currentTimeMillis() - start, savedStack.getName());

        return response;
    }

    private void fillInstanceMetadata(Stack stack) {
        long privateIdNumber = 0;
        for (InstanceGroup instanceGroup : stack.getInstanceGroups()) {
            for (InstanceMetaData instanceMetaData : instanceGroup.getAllInstanceMetaData()) {
                instanceMetaData.setPrivateId(privateIdNumber);
                instanceMetaData.setInstanceStatus(InstanceStatus.REQUESTED);
                privateIdNumber++;
            }
        }
    }

    private boolean shouldUseBaseImage(ClusterRequest clusterRequest) {
        return clusterRequest.getAmbariRepoDetailsJson() != null || clusterRequest.getAmbariStackDetails() != null;
    }

    private Stack prepareSharedServiceIfNeed(StackRequest stackRequest, Stack stack, String stackName) throws TransactionService.TransactionExecutionException {
        if (stackRequest.getClusterRequest() != null && stackRequest.getClusterRequest().getConnectedCluster() != null) {
            try {
                long start = System.currentTimeMillis();
                Optional<StackInputs> stackInputs = sharedServiceConfigProvider.prepareDatalakeConfigs(stack.getCluster().getBlueprint(), stack);
                if (stackInputs.isPresent()) {
                    stack = sharedServiceConfigProvider.updateStackinputs(stackInputs.get(), stack);
                }
                LOGGER.info("Cluster object and its dependencies has been created in {} ms for stack {}", System.currentTimeMillis() - start, stackName);
            } catch (BadRequestException e) {
                stackService.delete(stack);
                throw e;
            }
        }
        return stack;
    }

    private boolean forceBaseImage(ClusterRequest clusterRequest) {
        return clusterRequest.getAmbariRepoDetailsJson() != null;
    }

    private void createClusterIfNeed(IdentityUser user, StackRequest stackRequest, Stack stack, String stackName, Blueprint blueprint) throws Exception {
        if (stackRequest.getClusterRequest() != null) {
            try {
                long start = System.currentTimeMillis();
                Cluster cluster = clusterCreationService.prepare(stackRequest.getClusterRequest(), stack, blueprint, user);
                LOGGER.info("Cluster object and its dependencies has been created in {} ms for stack {}", System.currentTimeMillis() - start, stackName);
                stack.setCluster(cluster);
            } catch (BadRequestException e) {
                stackService.delete(stack);
                throw e;
            }
        }
    }

    private void validateAccountPreferences(Stack stack, IdentityUser user) {
        try {
            accountPreferencesValidator.validate(stack, user.getAccount(), user.getUserId());
        } catch (AccountPreferencesValidationException e) {
            throw new BadRequestException(e.getMessage(), e);
        }
    }
}
