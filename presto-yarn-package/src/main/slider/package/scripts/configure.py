#!/usr/bin/env python
"""
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

"""
from resource_management import *
import ast

def set_configuration(component=None):
    """
    Set configuration based on the component role. The jinja2 templates are populated from params.py
    :param component: COORDINATOR or WORKER
    :return:
    """
    import params

    _directory(params.conf_dir, params)
    _directory(params.catalog_dir, params)
    _directory(params.pid_dir, params)
    _directory(params.log_dir, params)

    _template_config("{params.conf_dir}/config.properties", params, component)
    _template_config("{params.conf_dir}/node.properties", params)
    

    if params.jvm_args:
        jvm_arg_list = ast.literal_eval(params.jvm_args)
        _store_configuration(jvm_arg_list, format("{params.conf_dir}/jvm.config"))

    if params.catalog_properties:
        catalog_dict = ast.literal_eval(params.catalog_properties)
        for key, value in catalog_dict.iteritems():
            _store_configuration(value, format("{params.catalog_dir}/{key}.properties"))


def _store_configuration(parameters, path):
    with open(path, 'a') as fw:
        for parameter in parameters:
            fw.write("%s\n" % parameter)
            
def _directory(path, params):
    Directory(path,
              owner=params.presto_user,
              group=params.user_group,
              recursive=True
              )


def _template_config(path, params, template_tag=None):
    TemplateConfig(format(path),
                   owner=params.presto_user,
                   group=params.user_group,
                   template_tag=template_tag
                   )
