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
package org.wso2.carbon.mediator.as4;

import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMNode;
import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.ConfigurationContextFactory;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.SynapseException;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.AbstractMediator;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.wso2.carbon.mediator.as4.compression.GZipCompressionDataHandler;
import org.wso2.carbon.mediator.as4.connection.AS4Connection;
import org.wso2.carbon.mediator.as4.datasources.InputStreamDataSource;
import org.wso2.carbon.mediator.as4.msg.AS4MessageIn;
import org.wso2.carbon.mediator.as4.msg.AS4Payload;
import org.wso2.carbon.mediator.as4.msg.MessageIdGenerator;
import org.wso2.carbon.mediator.as4.msg.impl.CollaborationInfo;
import org.wso2.carbon.mediator.as4.msg.impl.From;
import org.wso2.carbon.mediator.as4.msg.impl.MessageInfo;
import org.wso2.carbon.mediator.as4.msg.impl.Messaging;
import org.wso2.carbon.mediator.as4.msg.impl.PartInfo;
import org.wso2.carbon.mediator.as4.msg.impl.PartProperties;
import org.wso2.carbon.mediator.as4.msg.impl.PartyId;
import org.wso2.carbon.mediator.as4.msg.impl.PartyInfo;
import org.wso2.carbon.mediator.as4.msg.impl.PayloadInfo;
import org.wso2.carbon.mediator.as4.msg.impl.Property;
import org.wso2.carbon.mediator.as4.msg.impl.To;
import org.wso2.carbon.mediator.as4.msg.impl.UserMessage;
import org.wso2.carbon.mediator.as4.pmode.PModeRepository;
import org.wso2.carbon.mediator.as4.pmode.impl.PMode;
import org.wso2.carbon.mediator.as4.temp.TmpCons;

import javax.activation.DataHandler;
import javax.activation.FileDataSource;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.soap.SOAPException;
import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Date;

/**
 * AS4 out bound implementation. This custom mediator implementation will generate the user message and invoke corner3
 * with provided address. Then response from the corner3 will be passed to next sequences.
 */
public class AS4OutboundMediator extends AbstractMediator {
    private static final Log log = LogFactory.getLog(AS4OutboundMediator.class);
    private JAXBContext jaxbMessagingContext;
    private PModeRepository pModeRepository;
    private String pModesLocation; //this can be set as custom class mediator properties "pModesLocation".
    private String axis2ClientConfigLocation;//This is configurable as custom class mediator property.
    private AS4Connection as4Connection;

    /**
     * Constructor for the custom class mediator implementation.
     */
    public AS4OutboundMediator() {
        try {
            this.jaxbMessagingContext = JAXBContext.newInstance(Messaging.class);
        } catch (JAXBException e) {
            log.error("Unable to create JAXB marshaller and unmarshaller - " + e.getMessage(), e);
            throw new SynapseException("Unable to create JAXB marshaller and unmarshaller - " + e.getMessage(), e);
        }
    }

    @Override
    public boolean mediate(MessageContext messageContext) {
        try {
            createPModeRepo();//to create pMode repository if not exist.
            createConfigContext();//to create config context if not exist.
            createAndSetUserMessageToMsgCtx(messageContext);

            if (messageContext.getProperty(AS4Constants.AS4_ENDPOINT_ADDRESS) == null) {
                throw new SynapseException("Endpoint URL not found in message context property - " + AS4Constants.AS4_ENDPOINT_ADDRESS);
            }
            String endpointUrl = messageContext.getProperty(AS4Constants.AS4_ENDPOINT_ADDRESS).toString();
            if (endpointUrl == null || endpointUrl.isEmpty()) {
                throw new SynapseException("Invalid endpoint url found, url - " + endpointUrl);
            }
            URL url = new URL(endpointUrl);

            org.apache.axis2.context.MessageContext axis2MsgCtx = ((Axis2MessageContext) messageContext).getAxis2MessageContext();


            org.apache.axis2.context.MessageContext responseContext = as4Connection.call(axis2MsgCtx, url);

            RelayUtils.buildMessage(responseContext);

            OMElement messageEl = responseContext.getEnvelope().getHeader().getFirstElement();
            InputStream userMessageStream = new ByteArrayInputStream(messageEl.toString().getBytes());
            Unmarshaller messagingUnMarshaller = jaxbMessagingContext.createUnmarshaller();
            Messaging responseMsg = (Messaging) messagingUnMarshaller.unmarshal(userMessageStream);

            axis2MsgCtx.setEnvelope(responseContext.getEnvelope());
//            messageContext.setEnvelope(responseContext.getEnvelope());
            messageContext.setProperty(AS4Constants.AS4_RESPONSE_MESSAGE, responseMsg.getSignalMessage());

            messageContext.setProperty(Constants.Configuration.ENABLE_SWA, false);
            axis2MsgCtx.setProperty(Constants.Configuration.ENABLE_SWA, false);

            messageContext.setProperty(SynapseConstants.PRESERVE_PROCESSED_HEADERS, "true");
            axis2MsgCtx.setProperty(SynapseConstants.PRESERVE_PROCESSED_HEADERS, true);

        } catch (SynapseException se) {
            throw se;
        } catch (Exception e) {
            log.error("Error while processing the file/folder", e);
            throw new SynapseException("Error while processing the file/folder", e);
        }
        return true;

    }

    /**
     * Helper method to create and set user message to message context.
     *
     * @param messageContext
     */
    private void createAndSetUserMessageToMsgCtx(MessageContext messageContext)
            throws JAXBException, IOException, XMLStreamException {
        Date processingStartTime = new Date();
        AS4MessageIn as4MessageIn = (AS4MessageIn) messageContext.getProperty(AS4Constants.AS4_IN_MESSAGE);
        if (as4MessageIn == null || as4MessageIn.getAgreementRef() == null
            || as4MessageIn.getAgreementRef().isEmpty()) {
            log.error("Incoming message object is invalid");
            throw new SynapseException("Incoming message is invalid, " + as4MessageIn);
        }
        PMode pMode = this.pModeRepository.findPModeFromAgreement(as4MessageIn.getAgreementRef());

        if (pMode == null) {
            log.error("Cannot find matching PMode for the message, agreementRef - "
                      + as4MessageIn.getAgreementRef());
            throw new SynapseException("Cannot find matching PMode for the message, agreementRef - "
                                       + as4MessageIn.getAgreementRef());
        }

        Messaging msgObjToSend = new Messaging();
        msgObjToSend.setMustUnderstand("true");

        UserMessage userMessage = new UserMessage();

        MessageInfo messageInfo = new MessageInfo();
        messageInfo.setTimestamp(processingStartTime);
        messageInfo.setMessageId(MessageIdGenerator.createMessageId()); //generate and set message id
        userMessage.setMessageInfo(messageInfo);

        PartyInfo partyInfo = new PartyInfo();
        From from = new From();

        PartyId fromPartyId = new PartyId();
        fromPartyId.setType(TmpCons.PARTY_ID_TYPE);//todo remove if need to test with holodeck
        fromPartyId.setValue(pMode.getInitiator().getParty());

        from.setPartyId(fromPartyId);
        from.setRole(pMode.getInitiator().getRole());

        To to = new To();

        PartyId toPartyId = new PartyId();
        toPartyId.setType(TmpCons.PARTY_ID_TYPE); //todo remove if need to test with holodeck
        toPartyId.setValue(pMode.getResponder().getParty());

        to.setPartyId(toPartyId);
        to.setRole(pMode.getResponder().getRole());

        partyInfo.setFrom(from);
        partyInfo.setTo(to);
        userMessage.setPartyInfo(partyInfo);

        CollaborationInfo collaborationInfo = new CollaborationInfo();
        collaborationInfo.setAgreementRef(pMode.getAgreement().getName());


        collaborationInfo.setService(pMode.getBusinessInfo().getService());
        collaborationInfo.setAction(pMode.getBusinessInfo().getAction());
        collaborationInfo.setConversationId(MessageIdGenerator.generateConversationId());
        userMessage.setCollaborationInfo(collaborationInfo);


        PayloadInfo payloadInfo = new PayloadInfo();
        for (AS4Payload payload : as4MessageIn.getPayloads()) {
            DataHandler dataHandler = null;
            switch (payload.getPayloadType()) {
                case FILE:
                    dataHandler = new GZipCompressionDataHandler(new FileDataSource(payload.getPayloadFile()));
                    break;
                case INPUT_STREAM:
                    dataHandler = new GZipCompressionDataHandler(new InputStreamDataSource(payload.getPayloadStream()));//todo test this
                    break;
            }
            if (dataHandler == null) { //current implementation throws if single payload processing fails.
                log.error("Cannot create valid data handler for the payload - " + payload.getPayloadType());
                throw new SynapseException("Cannot create valid data handler for the payload - "
                                           + payload.getPayloadType());
            }
            String contentId = MessageIdGenerator.generateContentId(messageInfo.getMessageId(), System.nanoTime());
            ((Axis2MessageContext) messageContext).getAxis2MessageContext().addAttachment(contentId, dataHandler);

            PartInfo partInfoN = new PartInfo();
            partInfoN.setHref(contentId);
            PartProperties partProperties = new PartProperties();

            Property compProperty = new Property();
            compProperty.setName(AS4Constants.COMPRESSION_TYPE);
            compProperty.setValue(dataHandler.getContentType()); //todo (do we need to support other types? do we need to add this to PMODE?)

            Property mimeProperty = new Property();
            mimeProperty.setName(AS4Constants.MIME_TYPE);
            mimeProperty.setValue(payload.getMimeType());

            partProperties.addPartProperty(compProperty);
            partProperties.addPartProperty(mimeProperty);
            partInfoN.setPartPropertiesObj(partProperties);
            payloadInfo.addPartInfo(partInfoN);
        }

        userMessage.setPayloadInfo(payloadInfo);

        msgObjToSend.setUserMessage(userMessage);

        Marshaller messagingMarshaller = jaxbMessagingContext.createMarshaller();

//            OMNode node = XMLUtils.toOM(in);
        OMNode node = AS4Utils.getOMNode(messagingMarshaller, msgObjToSend);

        SOAPEnvelope envelope = OMAbstractFactory.getSOAP12Factory().createSOAPEnvelope();
        envelope.addChild(OMAbstractFactory.getSOAP12Factory().createSOAPHeader());
        envelope.addChild(OMAbstractFactory.getSOAP12Factory().createSOAPBody());
        envelope.getHeader().addChild(node);

        messageContext.setEnvelope(envelope);
    }

    /**
     * Helper method to create PMode repository if it not exist.
     */
    private void createPModeRepo() {
        if (pModeRepository == null) {
            initPModeRepository();
        }
    }

    /**
     * Helper method which helps invoking this once per proxy.
     */
    private synchronized void initPModeRepository() {
        if (pModeRepository == null) {
            pModeRepository = new PModeRepository(pModesLocation);
        }
    }

    /**
     * Helper method to create clientConfig for invoking corner3.
     *
     * @throws AxisFault
     */
    private void createConfigContext() throws AxisFault, SOAPException {
        if (as4Connection == null) {
            initConfigContext();
        }
    }

    /**
     * Method to help making single config for each proxy.
     *
     * @throws AxisFault
     */
    private synchronized void initConfigContext() throws AxisFault, SOAPException {
        if (as4Connection == null) {
            if (axis2ClientConfigLocation != null && !axis2ClientConfigLocation.isEmpty()) {
                ConfigurationContext configurationContext = ConfigurationContextFactory.createConfigurationContextFromFileSystem(null, axis2ClientConfigLocation);
                as4Connection = new AS4Connection(configurationContext);
            } else {
                as4Connection = new AS4Connection();
            }
        }
    }

    public String getPModesLocation() {
        return pModesLocation;
    }

    public void setPModesLocation(String pModesLocation) {
        this.pModesLocation = pModesLocation;
    }

    public String getAxis2ClientConfigLocation() {
        return axis2ClientConfigLocation;
    }

    public void setAxis2ClientConfigLocation(String axis2ClientConfigLocation) {
        this.axis2ClientConfigLocation = axis2ClientConfigLocation;
    }
}
