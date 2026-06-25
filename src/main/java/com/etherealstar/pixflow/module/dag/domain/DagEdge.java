package com.etherealstar.pixflow.module.dag.domain;

import java.util.Objects;

/**
 * 有向边（DagEdge），对应 DAG JSON 中 edges 数组的单个元素。
 *
 * <pre>
 * { "from": "n1", "to": "n2" }
 * </pre>
 *
 * 语义：from 节点的输出是 to 节点的输入，故 from 必须先于 to 完成。
 */
public class DagEdge {

    private String from;
    private String to;

    public DagEdge() {
    }

    public DagEdge(String from, String to) {
        this.from = from;
        this.to = to;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DagEdge dagEdge = (DagEdge) o;
        return Objects.equals(from, dagEdge.from) && Objects.equals(to, dagEdge.to);
    }

    @Override
    public int hashCode() {
        return Objects.hash(from, to);
    }

    @Override
    public String toString() {
        return "DagEdge{from='" + from + "', to='" + to + "'}";
    }
}
