package com.idfcfirstbank.integration.brandrouter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * CONFIG-AS-DATA routing (BRD §6): the partitioned brands go to Kafka; everything
 * else falls to ActiveMQ. Adding a brand to {@code partitions} is a config row,
 * NOT code.
 */
@ConfigurationProperties(prefix = "idfc.brand-router")
public class BrandRouterProperties {

    /** Brand names that have a Kafka partition (e.g. GODREJ, BOSCH, TCL, KENSTAR, BPL). */
    private List<String> partitions = List.of();
    private String responseTopic = "brand.response.v1";
    private String activemqQueue = "brand.activemq.queue";

    public List<String> getPartitions() { return partitions; }
    public void setPartitions(List<String> partitions) { this.partitions = partitions; }
    public String getResponseTopic() { return responseTopic; }
    public void setResponseTopic(String responseTopic) { this.responseTopic = responseTopic; }
    public String getActivemqQueue() { return activemqQueue; }
    public void setActivemqQueue(String activemqQueue) { this.activemqQueue = activemqQueue; }
}
