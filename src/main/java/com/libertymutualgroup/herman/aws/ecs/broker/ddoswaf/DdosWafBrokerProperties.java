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
package com.libertymutualgroup.herman.aws.ecs.broker.ddoswaf;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DdosWafBrokerProperties {

    private String ddosWafLambda;
    private List<WafRuleAction> ruleActions;

    public String getDdosWafLambda() {
        return ddosWafLambda;
    }

    public void setDdosWafLambda(String ddosWafLambda) {
        this.ddosWafLambda = ddosWafLambda;
    }

    public List<WafRuleAction> getRuleActions() {
        return ruleActions;
    }

    public void setRuleActions(List<WafRuleAction> ruleActions) {
        this.ruleActions = ruleActions;
    }

    public DdosWafBrokerProperties withDdosWafLambda(final String ddosWafLambda) {
        this.ddosWafLambda = ddosWafLambda;
        return this;
    }

    public DdosWafBrokerProperties withRuleActions(
        final List<WafRuleAction> ruleActions) {
        this.ruleActions = ruleActions;
        return this;
    }

    @Override
    public String toString() {
        return "DdosWafBrokerProperties{" +
            "ddosWafLambda='" + ddosWafLambda + '\'' +
            ", ruleActions=" + ruleActions +
            '}';
    }
}