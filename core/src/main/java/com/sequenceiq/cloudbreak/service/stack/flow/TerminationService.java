package com.sequenceiq.cloudbreak.service.stack.flow;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.sequenceiq.cloudbreak.api.model.DetailedStackStatus;
import com.sequenceiq.cloudbreak.api.model.stack.instance.InstanceStatus;
import com.sequenceiq.cloudbreak.core.bootstrap.service.OrchestratorTypeResolver;
import com.sequenceiq.cloudbreak.domain.stack.Stack;
import com.sequenceiq.cloudbreak.domain.stack.cluster.Cluster;
import com.sequenceiq.cloudbreak.domain.stack.instance.InstanceGroup;
import com.sequenceiq.cloudbreak.domain.stack.instance.InstanceMetaData;
import com.sequenceiq.cloudbreak.repository.InstanceGroupRepository;
import com.sequenceiq.cloudbreak.repository.InstanceMetaDataRepository;
import com.sequenceiq.cloudbreak.repository.StackUpdater;
import com.sequenceiq.cloudbreak.service.TransactionService;
import com.sequenceiq.cloudbreak.service.cluster.flow.ClusterTerminationService;
import com.sequenceiq.cloudbreak.service.stack.StackService;

@Service
public class TerminationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TerminationService.class);

    private static final String DELIMITER = "_";

    @Inject
    private StackService stackService;

    @Inject
    private InstanceGroupRepository instanceGroupRepository;

    @Inject
    private StackUpdater stackUpdater;

    @Inject
    private InstanceMetaDataRepository instanceMetaDataRepository;

    @Inject
    private ClusterTerminationService clusterTerminationService;

    @Inject
    private OrchestratorTypeResolver orchestratorTypeResolver;

    @Inject
    private TransactionService transactionService;

    public void finalizeTermination(Long stackId, boolean force) {
        Stack stack = stackService.getByIdWithLists(stackId);
        try {
            Date now = new Date();
            String terminatedName = stack.getName() + DELIMITER + now.getTime();
            Cluster cluster = stack.getCluster();
            boolean containerOrhestrator = orchestratorTypeResolver.resolveType(stack.getOrchestrator()).containerOrchestrator();
            transactionService.required(() -> {
                if (cluster != null && containerOrhestrator) {
                    clusterTerminationService.deleteClusterComponents(cluster.getId());
                    clusterTerminationService.finalizeClusterTermination(cluster.getId());
                } else {
                    if (!force && cluster != null) {
                        throw new TerminationFailedException(String.format("There is a cluster installed on stack '%s', terminate it first!.", stackId));
                    } else if (cluster != null) {
                        clusterTerminationService.finalizeClusterTermination(cluster.getId());
                    }
                }
                stack.setCredential(null);
                stack.setNetwork(null);
                stack.setFlexSubscription(null);
                stack.setName(terminatedName);
                terminateInstanceGroups(stack);
                terminateMetaDataInstances(stack);
                stackService.save(stack);
                stackUpdater.updateStackStatus(stackId, DetailedStackStatus.DELETE_COMPLETED, "Stack was terminated successfully.");
                return null;
            });
        } catch (Exception ex) {
            LOGGER.error("Failed to terminate cluster infrastructure. Stack id {}", stack.getId());
            throw new TerminationFailedException(ex);
        }
    }

    private void terminateInstanceGroups(Stack stack) {
        for (InstanceGroup instanceGroup : stack.getInstanceGroups()) {
            instanceGroup.setSecurityGroup(null);
            instanceGroup.setTemplate(null);
            instanceGroupRepository.save(instanceGroup);
        }

    }

    private void terminateMetaDataInstances(Stack stack) {
        List<InstanceMetaData> instanceMetaDatas = new ArrayList<>();
        for (InstanceMetaData metaData : stack.getNotDeletedInstanceMetaDataSet()) {
            long timeInMillis = Calendar.getInstance().getTimeInMillis();
            metaData.setTerminationDate(timeInMillis);
            metaData.setInstanceStatus(InstanceStatus.TERMINATED);
            instanceMetaDatas.add(metaData);
        }
        instanceMetaDataRepository.save(instanceMetaDatas);
    }
}