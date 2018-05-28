{% if grains['init'] == 'upstart' %}
    {% set is_systemd = False %}
{% else %}
    {% set is_systemd = True %}
{% endif %}
{% set is_local_ldap = salt['pillar.get']('ldap:local', False) %}

{% set gateway = {} %}
{% do gateway.update({
    'is_systemd' : is_systemd,
    'is_local_ldap' : is_local_ldap
}) %}

{% set used_vdf_url = salt['pillar.get']('hdp:stack:vdf-url') %}

{% set hdp_repo_url = salt['cmd.run']('/usr/bin/extract-repo-url-from-vdf.sh ' + used_vdf_url + ' HDP') %}
{% set hdf_repo_url = salt['cmd.run']('/usr/bin/extract-repo-url-from-vdf.sh ' + used_vdf_url + ' HDF') %}
{% set hdp_utils_repo_url = salt['cmd.run']('/usr/bin/extract-repo-url-from-vdf.sh ' + used_vdf_url + ' HDP-UTILS') %}

{% set vdf_based_urls = {} %}

{% do vdf_based_urls.update({
    'hdp_repo_url': hdp_repo_url,
    'hdf_repo_url': hdf_repo_url,
    'hdp_utils_repo_url': hdp_utils_repo_url
}) %}