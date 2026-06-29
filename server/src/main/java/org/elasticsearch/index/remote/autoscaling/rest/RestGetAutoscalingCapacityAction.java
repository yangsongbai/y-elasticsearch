package org.elasticsearch.index.remote.autoscaling.rest;

import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestStatus;

import java.util.Arrays;
import java.util.List;

public class RestGetAutoscalingCapacityAction extends BaseRestHandler {

    @Override
    public String getName() {
        return "get_autoscaling_capacity";
    }

    @Override
    public List<Route> routes() {
        return Arrays.asList(new Route(RestRequest.Method.GET, "/_autoscaling/capacity"));
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) {
        return channel -> channel.sendResponse(
            new BytesRestResponse(RestStatus.OK, "application/json", "{\"status\":\"ok\"}")
        );
    }
}
