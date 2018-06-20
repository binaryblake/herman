/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.libertymutualgroup.herman.aws.ecs.loadbalancing;

import com.amazonaws.services.cloudformation.model.Tag;
import com.amazonaws.services.ecs.model.ContainerDefinition;
import com.amazonaws.services.ecs.model.LoadBalancer;
import com.amazonaws.services.elasticloadbalancing.model.HealthCheck;
import com.amazonaws.services.elasticloadbalancingv2.AmazonElasticLoadBalancing;
import com.amazonaws.services.elasticloadbalancingv2.model.Action;
import com.amazonaws.services.elasticloadbalancingv2.model.ActionTypeEnum;
import com.amazonaws.services.elasticloadbalancingv2.model.AddTagsRequest;
import com.amazonaws.services.elasticloadbalancingv2.model.Certificate;
import com.amazonaws.services.elasticloadbalancingv2.model.CreateListenerRequest;
import com.amazonaws.services.elasticloadbalancingv2.model.CreateLoadBalancerRequest;
import com.amazonaws.services.elasticloadbalancingv2.model.CreateLoadBalancerResult;
import com.amazonaws.services.elasticloadbalancingv2.model.CreateTargetGroupRequest;
import com.amazonaws.services.elasticloadbalancingv2.model.DescribeListenersRequest;
import com.amazonaws.services.elasticloadbalancingv2.model.DescribeLoadBalancersRequest;
import com.amazonaws.services.elasticloadbalancingv2.model.DescribeTargetGroupsRequest;
import com.amazonaws.services.elasticloadbalancingv2.model.Listener;
import com.amazonaws.services.elasticloadbalancingv2.model.LoadBalancerAttribute;
import com.amazonaws.services.elasticloadbalancingv2.model.LoadBalancerNotFoundException;
import com.amazonaws.services.elasticloadbalancingv2.model.LoadBalancerState;
import com.amazonaws.services.elasticloadbalancingv2.model.ModifyListenerRequest;
import com.amazonaws.services.elasticloadbalancingv2.model.ModifyLoadBalancerAttributesRequest;
import com.amazonaws.services.elasticloadbalancingv2.model.ModifyTargetGroupAttributesRequest;
import com.amazonaws.services.elasticloadbalancingv2.model.ModifyTargetGroupRequest;
import com.amazonaws.services.elasticloadbalancingv2.model.ProtocolEnum;
import com.amazonaws.services.elasticloadbalancingv2.model.SetSecurityGroupsRequest;
import com.amazonaws.services.elasticloadbalancingv2.model.SetSubnetsRequest;
import com.amazonaws.services.elasticloadbalancingv2.model.TargetGroup;
import com.amazonaws.services.elasticloadbalancingv2.model.TargetGroupAttribute;
import com.libertymutualgroup.herman.aws.AwsExecException;
import com.libertymutualgroup.herman.aws.ecs.EcsPortHandler;
import com.libertymutualgroup.herman.aws.ecs.EcsPushDefinition;
import com.libertymutualgroup.herman.aws.ecs.cluster.EcsClusterMetadata;
import com.libertymutualgroup.herman.logging.HermanLogger;
import com.libertymutualgroup.herman.task.common.CommonTaskProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class EcsLoadBalancerV2Handler {

    private static final Logger LOGGER = LoggerFactory.getLogger(EcsLoadBalancerV2Handler.class);
    private static final String HTTPS = "HTTPS";

    private AmazonElasticLoadBalancing elbClient;
    private CertHandler certHandler;
    private HermanLogger buildLogger;
    private DnsRegistrar dnsRegistrar;
    private CommonTaskProperties taskProperties;

    public EcsLoadBalancerV2Handler(AmazonElasticLoadBalancing elbClient, CertHandler certHandler,
        DnsRegistrar dnsRegistrar, HermanLogger buildLogger, CommonTaskProperties taskProperties) {
        this.elbClient = elbClient;
        this.certHandler = certHandler;
        this.buildLogger = buildLogger;
        this.dnsRegistrar = dnsRegistrar;
        this.taskProperties = taskProperties;
    }

    public LoadBalancer createLoadBalancer(EcsClusterMetadata clusterMetadata, EcsPushDefinition definition) {

        String appName = definition.getAppName();
        EcsPortHandler portHandler = new EcsPortHandler();

        String protocol = definition.getService().getProtocol();
        if (protocol == null) {
            protocol = HTTPS;
        }

        String urlPrefix = appName;
        if (definition.getService().getUrlPrefixOverride() != null) {
            urlPrefix = definition.getService().getUrlPrefixOverride();
        }
        String urlSuffix = definition.getService().getUrlSuffix();

        DeriveCertResult deriveCertResult = certHandler.deriveCert(protocol, urlSuffix, urlPrefix);

        ContainerDefinition webContainer = portHandler.findContainerWithExposedPort(definition, true);
        Integer containerPort = webContainer.getPortMappings().get(0).getContainerPort();
        String containerName = webContainer.getName();

        // Set scheme and subnets
        boolean isInternetFacingUrlScheme = certHandler.isInternetFacingUrlScheme(deriveCertResult.getSslCertificate(),
            definition.getService().getUrlSchemeOverride());
        boolean isUsingInternalSubnets = true;
        String elbScheme;
        List<String> elbSubnets;
        if (isInternetFacingUrlScheme || "internet-facing".equals(definition.getService().getElbSchemeOverride())) {
            elbScheme = "internet-facing";
            isUsingInternalSubnets = false;
            elbSubnets = clusterMetadata.getPublicSubnets();
        } else {
            elbScheme = "internal";
            elbSubnets = clusterMetadata.getElbSubnets();
        }

        // Set security groups - Akamai groups set for internet-facing apps
        List<String> elbSecurityGroups = new ArrayList<>();
        if (isInternetFacingUrlScheme && HTTPS.equals(protocol)) {
            elbSecurityGroups.addAll(clusterMetadata.getAkamaiSecurityGroup());
        } else {
            elbSecurityGroups.addAll(clusterMetadata.getElbSecurityGroups());
        }

        HealthCheck healthCheck = definition.getService().getHealthCheck();
        configureHealthCheckDefaults(healthCheck);

        List<com.amazonaws.services.elasticloadbalancingv2.model.Tag> tags = getElbTagList(
            clusterMetadata.getClusterCftStackTags(), appName);
        if (definition.getNotificationWebhook() != null) {
            tags.add(new com.amazonaws.services.elasticloadbalancingv2.model.Tag().withKey("NotificationWebhook")
                .withValue(definition.getNotificationWebhook()));
        }

        com.amazonaws.services.elasticloadbalancingv2.model.LoadBalancer loadBalancer = findLoadBalancer(appName);
        TargetGroup grp;
        if (loadBalancer == null) {
            buildLogger.addLogEntry("Creating a new ALB: " + appName);

            CreateTargetGroupRequest ctgr = new CreateTargetGroupRequest().withName(appName)
                .withHealthCheckIntervalSeconds(healthCheck.getInterval())
                .withHealthCheckPath(healthCheck.getTarget())
                .withHealthCheckPort("traffic-port")
                .withPort(443)
                .withHealthCheckTimeoutSeconds(healthCheck.getTimeout())
                .withHealthyThresholdCount(healthCheck.getHealthyThreshold())
                .withUnhealthyThresholdCount(healthCheck.getUnhealthyThreshold())
                .withProtocol(ProtocolEnum.HTTPS)
                .withVpcId(clusterMetadata.getVpcId());

            grp = elbClient.createTargetGroup(ctgr).getTargetGroups().get(0);

            CreateLoadBalancerResult res = elbClient.createLoadBalancer(new CreateLoadBalancerRequest()
                .withSubnets(elbSubnets)
                .withScheme(elbScheme)
                .withSecurityGroups(elbSecurityGroups)
                .withName(appName).withTags(tags));
            loadBalancer = res.getLoadBalancers().get(0);

            elbClient.createListener(new CreateListenerRequest().withLoadBalancerArn(loadBalancer.getLoadBalancerArn())
                .withCertificates(new Certificate().withCertificateArn(deriveCertResult.getCertArn()))
                .withProtocol(ProtocolEnum.HTTPS)
                .withPort(443).withDefaultActions(
                    new Action().withTargetGroupArn(grp.getTargetGroupArn()).withType(ActionTypeEnum.Forward)));
            waitForALBCreate(loadBalancer.getLoadBalancerArn());

        } else {
            String arn = loadBalancer.getLoadBalancerArn();
            buildLogger.addLogEntry("Updating existing ALB: " + arn);

            buildLogger.addLogEntry("... Reset SG");
            elbClient.setSecurityGroups(
                new SetSecurityGroupsRequest().withLoadBalancerArn(arn).withSecurityGroups(elbSecurityGroups));

            buildLogger.addLogEntry("... Reset subnets");
            elbClient.setSubnets(new SetSubnetsRequest().withLoadBalancerArn(arn).withSubnets(elbSubnets));

            buildLogger.addLogEntry("... Reset tags");
            elbClient.addTags(new AddTagsRequest().withResourceArns(arn)
                .withTags(tags));

            grp = elbClient
                .describeTargetGroups(new DescribeTargetGroupsRequest().withNames(appName))
                .getTargetGroups().get(0);
            elbClient.modifyTargetGroup(new ModifyTargetGroupRequest()
                .withTargetGroupArn(grp.getTargetGroupArn())
                .withHealthCheckIntervalSeconds(healthCheck.getInterval())
                .withHealthCheckPath(healthCheck.getTarget())
                .withHealthCheckPort("traffic-port")
                .withHealthCheckTimeoutSeconds(healthCheck.getTimeout())
                .withHealthyThresholdCount(healthCheck.getHealthyThreshold())
                .withUnhealthyThresholdCount(healthCheck.getUnhealthyThreshold()));

            Listener lis = elbClient.describeListeners(new DescribeListenersRequest().withLoadBalancerArn(arn))
                .getListeners().get(0);
            elbClient.modifyListener(new ModifyListenerRequest().withListenerArn(lis.getListenerArn())
                .withCertificates(new Certificate().withCertificateArn(deriveCertResult.getCertArn())));
        }

        modifyTargetGroupAttributes(definition, grp);
        modifyLoadBalancerAttributes(definition, loadBalancer.getLoadBalancerArn());

        buildLogger.addLogEntry("Updating DNS registration");
        String registeredUrl = urlPrefix + "." + definition.getService().getUrlSuffix();
        if (isUsingInternalSubnets) {
            dnsRegistrar.registerDns(registeredUrl, loadBalancer.getDNSName(), appName,
                clusterMetadata.getClusterCftStackTags());
            buildLogger.addLogEntry("... URL Registered: " + protocol.toLowerCase() + "://" + registeredUrl);
        } else {
            buildLogger.addLogEntry(
                "... Raw ELB DNS for Akamai: " + protocol.toLowerCase() + "://" + loadBalancer.getDNSName());
            buildLogger.addLogEntry("... Expected Akamai url: " + protocol.toLowerCase() + "://" + registeredUrl);
        }

        buildLogger.addLogEntry("ALB updates complete: " + loadBalancer.getLoadBalancerArn());
        return new LoadBalancer().withContainerName(containerName).withContainerPort(containerPort)
            .withTargetGroupArn(grp.getTargetGroupArn());
    }

    private void modifyTargetGroupAttributes(EcsPushDefinition definition, TargetGroup grp) {
        List<TargetGroupAttribute> attrs = new ArrayList<>();
        if (definition.getService().getAppStickinessCookie() != null) {
            attrs.add(new TargetGroupAttribute()
                .withKey("stickiness.enabled")
                .withValue("true"));
            attrs.add(new TargetGroupAttribute()
                .withKey("stickiness.type")
                .withValue("lb_cookie"));
        }
        attrs.add(new TargetGroupAttribute().withKey("deregistration_delay.timeout_seconds").withValue("30"));

        buildLogger.addLogEntry("... Modifying target group attributes");
        elbClient.modifyTargetGroupAttributes(new ModifyTargetGroupAttributesRequest()
            .withTargetGroupArn(grp.getTargetGroupArn()).withAttributes(attrs));
    }

    private void modifyLoadBalancerAttributes(EcsPushDefinition definition, String loadBalancerArn) {
        List<LoadBalancerAttribute> attributes = new ArrayList<>();
        if (definition.getAlbTimeout() != null) {
            attributes.add(new LoadBalancerAttribute()
                .withKey("idle_timeout.timeout_seconds")
                .withValue(definition.getAlbTimeout()));
        }

        if (!attributes.isEmpty()) {
            buildLogger.addLogEntry("... Modifying load balancer attributes");
            elbClient.modifyLoadBalancerAttributes(new ModifyLoadBalancerAttributesRequest()
                .withLoadBalancerArn(loadBalancerArn)
                .withAttributes(attributes));
        }
    }

    private void configureHealthCheckDefaults(HealthCheck healthCheck) {
        if (healthCheck.getInterval() == null) {
            healthCheck.setInterval(30);
        }
        if (healthCheck.getHealthyThreshold() == null) {
            healthCheck.setHealthyThreshold(2);
        }
        if (healthCheck.getTimeout() == null) {
            healthCheck.setTimeout(10);
        }
        if (healthCheck.getUnhealthyThreshold() == null) {
            healthCheck.setUnhealthyThreshold(10);
        }
    }

    private com.amazonaws.services.elasticloadbalancingv2.model.LoadBalancer findLoadBalancer(String appName) {
        try {
            List<com.amazonaws.services.elasticloadbalancingv2.model.LoadBalancer> list = elbClient
                .describeLoadBalancers(new DescribeLoadBalancersRequest().withNames(appName)).getLoadBalancers();
            if (!list.isEmpty()) {
                return list.get(0);
            }
        } catch (LoadBalancerNotFoundException e) {
            LOGGER.debug("Error getting ELB: " + appName, e);
        }
        return null;
    }

    private List<com.amazonaws.services.elasticloadbalancingv2.model.Tag> getElbTagList(List<Tag> tags,
        String appName) {
        List<com.amazonaws.services.elasticloadbalancingv2.model.Tag> result = new ArrayList<>();
        for (Tag cftTag : tags) {
            if ("Name".equals(cftTag.getKey())) {
                result.add(new com.amazonaws.services.elasticloadbalancingv2.model.Tag()
                    .withKey(this.taskProperties.getClusterTagKey())
                    .withValue(cftTag.getValue()));
                result.add(new com.amazonaws.services.elasticloadbalancingv2.model.Tag().withKey("Name")
                    .withValue(appName));
            } else {
                result.add(new com.amazonaws.services.elasticloadbalancingv2.model.Tag().withKey(cftTag.getKey())
                    .withValue(cftTag.getValue()));
            }
        }
        return result;
    }


    private void waitForALBCreate(String arn) {
        buildLogger.addLogEntry("... Waiting for ALB create");
        for (int i = 0; i < 60; i++) {
            List<com.amazonaws.services.elasticloadbalancingv2.model.LoadBalancer> balancer = elbClient
                .describeLoadBalancers(
                    new com.amazonaws.services.elasticloadbalancingv2.model.DescribeLoadBalancersRequest()
                        .withLoadBalancerArns(arn)).getLoadBalancers();
            if (!balancer.isEmpty()) {
                LoadBalancerState state = balancer.get(0).getState();
                buildLogger.addLogEntry("... ALB state is: " + state.getCode());
                if ("active".equalsIgnoreCase(state.getCode())) {
                    return;
                }
            }

            try {
                Thread.sleep(10000);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                buildLogger.addLogEntry("Interrupted while polling");
                throw new AwsExecException("Interrupted while polling");
            }


        }
    }

}