/*
* Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
* WSO2 Inc. licenses this file to you under the Apache License,
* Version 2.0 (the "License"); you may not use this file except
* in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied. See the License for the
* specific language governing permissions and limitations
* under the License.
*/
package org.wso2.carbon.mediator.as4.msg.impl;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * Jaxb bean class for <PartInfo></PartInfo> element.
 */
@XmlRootElement(name = "PartInfo")
@XmlAccessorType(XmlAccessType.FIELD)
public class PartInfo {
    @XmlAttribute(name="href")
    private String href;

    @XmlElement(name="PartProperties")
    private PartProperties partProperties;

    public String getHref() {
        return href;
    }


    public void setHref(String href) {
        this.href = href;
    }

    public PartProperties getPartPropertiesObj() {
        return partProperties;
    }


    public void setPartPropertiesObj(PartProperties partProperties) {
        this.partProperties = partProperties;
    }

}
