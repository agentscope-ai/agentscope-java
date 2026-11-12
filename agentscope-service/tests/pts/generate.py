#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Create a self-contained PTS/JMeter plan without credentials."""
from pathlib import Path
import argparse
import xml.etree.ElementTree as ET


def generate(output):
    root = ET.Element('jmeterTestPlan', version='1.2', properties='5.0', jmeter='5.6.3')
    tree = ET.SubElement(root, 'hashTree')
    plan = ET.SubElement(tree, 'TestPlan', guiclass='TestPlanGui', testclass='TestPlan', testname='Managed Agents Sessions', enabled='true')
    ET.SubElement(plan, 'boolProp', name='TestPlan.functional_mode').text = 'false'
    ET.SubElement(plan, 'elementProp', name='TestPlan.user_defined_variables', elementType='Arguments', guiclass='ArgumentsPanel', testclass='Arguments', testname='Variables', enabled='true')
    group_tree = ET.SubElement(tree, 'hashTree')
    group = ET.SubElement(group_tree, 'ThreadGroup', guiclass='ThreadGroupGui', testclass='ThreadGroup', testname='Session clients', enabled='true')
    for key, value in {'num_threads':'${__P(threads,1)}', 'ramp_time':'${__P(rampSeconds,30)}', 'duration':'${__P(durationSeconds,600)}', 'delay':'0', 'on_sample_error':'continue'}.items():
        ET.SubElement(group, 'stringProp', name='ThreadGroup.'+key).text = value
    ET.SubElement(group, 'boolProp', name='ThreadGroup.scheduler').text = 'true'
    loop = ET.SubElement(group, 'elementProp', name='ThreadGroup.main_controller', elementType='LoopController', guiclass='LoopControlPanel', testclass='LoopController', testname='Loop', enabled='true')
    ET.SubElement(loop, 'boolProp', name='LoopController.continue_forever').text = 'false'
    ET.SubElement(loop, 'stringProp', name='LoopController.loops').text = '-1'
    sampler_tree = ET.SubElement(group_tree, 'hashTree')
    sampler = ET.SubElement(sampler_tree, 'JSR223Sampler', guiclass='TestBeanGUI', testclass='JSR223Sampler', testname='session.complete', enabled='true')
    for key, value in {'scriptLanguage':'groovy', 'cacheKey':'true', 'script':Path(__file__).with_name('session.groovy').read_text(), 'parameters':'', 'filename':''}.items():
        ET.SubElement(sampler, 'stringProp', name=key).text = value
    ET.SubElement(sampler_tree, 'hashTree')
    ET.indent(root)
    Path(output).write_bytes(ET.tostring(root, encoding='utf-8', xml_declaration=True))

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    generate(parser.parse_args().output)
