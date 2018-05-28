libxml2-utils:
    pkg.installed

{%- from 'gateway/settings.sls' import vdf_based_urls with context %}

{% set os = grains['os'] | lower ~ grains['osmajorrelease'] %}

{% if salt['pillar.get']('hdp:stack:vdf-url') != None %}

{% if 'HDF' in salt['pillar.get']('hdp:stack:repoid') %}

create_hdf_repo:
  pkgrepo.managed:
    - name: "deb {{ vdf_based_urls.hdf_repo_url }} HDF main"
    - file: /etc/apt/sources.list.d/hdf.list
    - clean_file: true

{% else %}

create_hdp_repo:
  pkgrepo.managed:
    - name: "deb {{ vdf_based_urls.hdp_repo_url }} HDP main"
    - file: /etc/apt/sources.list.d/hdp.list
    - clean_file: true

{% endif %}

create_hdp_utils_repo:
  pkgrepo.managed:
    - name: "deb {{ vdf_based_urls.hdp_utils_repo_url }} HDP-UTILS main"
    - file: /etc/apt/sources.list.d/hdp-utils.list
    - clean_file: true

{% else %}

create_hdp_repo:
  pkgrepo.managed:
    - name: "deb {{ salt['pillar.get']('hdp:stack:' + os) }} HDP main"
    - file: /etc/apt/sources.list.d/hdp.list

create_hdp_utils_repo:
  pkgrepo.managed:
    - name: "deb {{ salt['pillar.get']('hdp:util:' + os) }} HDP-UTILS main"
    - file: /etc/apt/sources.list.d/hdp-utils.list
{% endif %}