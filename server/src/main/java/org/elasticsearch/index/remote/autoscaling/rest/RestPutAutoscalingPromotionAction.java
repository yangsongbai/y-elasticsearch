package org.elasticsearch.index.remote.autoscaling.rest;

import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestStatus;

import java.util.Arrays;
import java.util.List;

public class RestPutAutoscalingPromotionAction extends BaseRestHandler {

    @Override
    public String getName() {
        return "put_autoscaling_promotion";
    }

    @Override
    public List<Route> routes() {
        return Arrays.asList(new Route(RestRequest.Method.PUT, "/_autoscaling/promotion/{id}"));
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) {
        String id = request.param("id");
        return channel -> channel.sendResponse(
            new BytesRestResponse(RestStatus.OK, "application/json",
                "{\"acknowledged\":true,\"id\":\"" + id + "\"}")
        );
    }
}
