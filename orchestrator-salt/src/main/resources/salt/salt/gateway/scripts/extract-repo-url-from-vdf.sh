#!/bin/bash

if [ -n "$1" ]
  then
    VDF_URL=$1
  else
    echo "Please specify a vdf-url"
    exit 1
fi

if [ -n "$2" ]
  then
    REPO=$2
  else
    echo "Please specify a reponame you want to extract a vdf-url"
    exit 1
fi

echo "Using vdf url $VDF_URL"

wget $VDF_URL --output-document=vdf.xml

#handle hdp repository
REPO_DATA=$(xmllint --xpath "//repository-version/repository-info/os/repo[reponame='$REPO']" vdf.xml)
if [ -n "#REPO_DATA" ]
  then
    HDP_BASE_URL=$(xmllint --xpath "//baseurl/text()" - <<<"$REPO_DATA")
    echo $HDP_BASE_URL
fi

rm vdf.xml