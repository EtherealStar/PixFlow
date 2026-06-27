package com.pixflow.infra.mq.topology;

public interface TopologyRegistrar {
    void register(QueueTopology topology);
}
