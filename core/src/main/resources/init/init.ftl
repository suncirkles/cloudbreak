#!/bin/bash

## logging
exec > >(tee /var/log/user-data.log|logger -t user-data -s 2>/dev/console) 2>&1

set -x

export CLOUD_PLATFORM="${cloudPlatform}"
export START_LABEL=${platformDiskStartLabel}
export PLATFORM_DISK_PREFIX=${platformDiskPrefix}
export LAZY_FORMAT_DISK_LIMIT=12
export IS_GATEWAY=${gateway?c}
export TMP_SSH_KEY="${tmpSshKey}"
export SSH_USER=${sshUser}
export SALT_BOOT_PASSWORD=${saltBootPassword}
export SALT_BOOT_SIGN_KEY=${signaturePublicKey}
export CB_CERT=${cbCert}

${customUserData}

/usr/bin/user-data-helper.sh "$@" &> /var/log/user-data.log


cat > /usr/bin/extract-repo-url-from-vdf.sh << 'EOF'
#!/bin/bash

if [ -n "$1" ]
  then
    VDF_URL=$1
  else
    echo "Please specify a vdf-url" >2
    exit 1
fi

if [ -n "$2" ]
  then
    REPO=$2
  else
    echo "Please specify a reponame you want to extract a vdf-url" >2
    exit 1
fi

wget $VDF_URL --output-document=vdf.xml > /dev/null 2>&1

#handle hdp repository
REPO_DATA=$(xmllint --xpath "//repository-version/repository-info/os/repo[reponame='$REPO']" vdf.xml)
if [ -n "#REPO_DATA" ]
  then
    HDP_BASE_URL=$(xmllint --xpath "//baseurl/text()" - <<<"$REPO_DATA")
    echo $HDP_BASE_URL
fi

rm vdf.xml

EOF

chmod +x /usr/bin/extract-repo-url-from-vdf.sh