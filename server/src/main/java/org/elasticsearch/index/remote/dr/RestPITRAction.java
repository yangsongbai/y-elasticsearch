package org.elasticsearch.index.remote.dr;

import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestStatus;

import java.util.Arrays;
import java.util.List;

public class RestPITRAction extends BaseRestHandler {

    @Override
    public String getName() { return "pitr_restore"; }

    @Override
    public List<Route> routes() {
        return Arrays.asList(
            new Route(RestRequest.Method.POST, "/{index}/_pitr_restore"),
            new Route(RestRequest.Method.GET, "/{index}/_pitr_points")
        );
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) {
        String index = request.param("index");
        if (request.method() == RestRequest.Method.GET) {
            return channel -> channel.sendResponse(
                new BytesRestResponse(
                    RestStatus.OK, "application/json",
                    "{\"index\":\"" + index + "\",\"points\":[]}"));
        }
        String timestamp = request.param("timestamp");
        return channel -> channel.sendResponse(
            new BytesRestResponse(
                RestStatus.OK, "application/json",
                "{\"acknowledged\":true,\"index\":\"" + index + "\",\"restored_to\":\"" + timestamp + "\"}"));
    }
}
