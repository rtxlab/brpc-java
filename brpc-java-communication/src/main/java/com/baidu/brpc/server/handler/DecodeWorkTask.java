/*
 * Copyright (c) 2018 Baidu, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.baidu.brpc.server.handler;

import com.baidu.brpc.client.RpcFuture;
import com.baidu.brpc.protocol.Protocol;
import com.baidu.brpc.protocol.Request;
import com.baidu.brpc.protocol.Response;
import com.baidu.brpc.protocol.http.BrpcHttpResponseEncoder;
import com.baidu.brpc.protocol.http.HttpRpcProtocol;
import com.baidu.brpc.protocol.push.SPHead;
import com.baidu.brpc.protocol.push.ServerPushPacket;
import com.baidu.brpc.protocol.push.ServerPushProtocol;
import com.baidu.brpc.server.CommunicationServer;
import com.baidu.brpc.server.ServerStatus;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

@Slf4j
@Setter
@Getter
@AllArgsConstructor
public class DecodeWorkTask implements Runnable {
    private CommunicationServer rpcServer;
    private Object packet;
    private Protocol protocol;
    private ChannelHandlerContext ctx;

    @Override
    public void run() {
        if (protocol instanceof HttpRpcProtocol) {
            FullHttpRequest fullHttpRequest = (FullHttpRequest) packet;
            try {
                if (fullHttpRequest.uri().equals("/favicon.ico")) {
                    FullHttpResponse fullHttpResponse =
                            new DefaultFullHttpResponse(HTTP_1_1, OK);
                    fullHttpResponse.headers().set(CONTENT_LENGTH, 0);
                    if (HttpUtil.isKeepAlive(fullHttpRequest)) {
                        fullHttpResponse.headers().set(CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                    }
                    BrpcHttpResponseEncoder encoder = new BrpcHttpResponseEncoder();
                    ByteBuf responseByteBuf = encoder.encode(fullHttpResponse);
                    ChannelFuture f = ctx.channel().writeAndFlush(responseByteBuf);
                    if (!HttpUtil.isKeepAlive(fullHttpRequest)) {
                        f.addListener(ChannelFutureListener.CLOSE);
                    }
                    return;
                } else if (fullHttpRequest.uri().equals("/") || fullHttpRequest.uri().equals("/status")) {
                    ServerStatus serverStatus = rpcServer.getServerStatus();

                    byte[] statusBytes = serverStatus.toString().getBytes("UTF-8");
                    FullHttpResponse fullHttpResponse =
                            new DefaultFullHttpResponse(HTTP_1_1, OK, Unpooled.wrappedBuffer(statusBytes));
                    fullHttpResponse.headers().set(CONTENT_TYPE, "text/html");
                    fullHttpResponse.headers().set(CONTENT_LENGTH, fullHttpResponse.content().readableBytes());
                    if (HttpUtil.isKeepAlive(fullHttpRequest)) {
                        fullHttpResponse.headers().set(CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
                    }
                    BrpcHttpResponseEncoder encoder = new BrpcHttpResponseEncoder();
                    ByteBuf responseByteBuf = encoder.encode(fullHttpResponse);
                    ChannelFuture f = ctx.channel().writeAndFlush(responseByteBuf);
                    if (!HttpUtil.isKeepAlive(fullHttpRequest)) {
                        f.addListener(ChannelFutureListener.CLOSE);
                    }
                    return;
                }
            } catch (Exception ex) {
                log.warn("send status info response failed:", ex);
                return;
            }
        } else if (protocol instanceof ServerPushProtocol) {
            SPHead spHead = ((ServerPushPacket) packet).getSpHead();
            if (spHead.getType() == SPHead.TYPE_PUSH_RESPONSE) {
                processClientResponse();
                return;
            }
        }

        Request request = null;
        Response response = protocol.createResponse();
        try {
            request = protocol.decodeRequest(packet);
            if (request.isHeartbeat()) {
                log.debug("receive hearbeat event from {}", ctx.channel().remoteAddress().toString());
            }
        } catch (Exception ex) {
            // throw request
            log.warn("decode request failed:", ex);
            response.setException(ex);
        } finally {
            if (request != null && request.getException() != null) {
                response.setException(request.getException());
            }
        }

        if (request == null || response.getException() != null) {
            try {
                ByteBuf byteBuf = protocol.encodeResponse(request, response);
                ChannelFuture channelFuture = ctx.channel().writeAndFlush(byteBuf);
                protocol.afterResponseSent(request, response, channelFuture);
            } catch (Exception ex) {
                log.warn("send response failed:", ex);
            }
            return;
        }

        ServerWorkTask workTask = new ServerWorkTask(rpcServer, protocol, request, response, ctx);
        if (request.isHeartbeat() || request.getRpcMethodInfo().getThreadPool() == rpcServer.getThreadPool()) {
            // service run in the current thread
            workTask.run();
        } else {
            // service run in individual thread
            request.getRpcMethodInfo().getThreadPool().submit(workTask);
        }
    }

    /**
     * 处理client的返回response
     */
    public void processClientResponse() {
        Response response;
        try {
            response = ((ServerPushProtocol) protocol).decodeServerPushResponse(packet, ctx);
        } catch (Exception e) {
            log.warn("decode response failed:", e);
            return;
        }
        if (response.getRpcFuture() != null) {
            log.debug("handle response, logId={}", response.getLogId());
            RpcFuture future = response.getRpcFuture();
            future.handleResponse(response);
        } else {
            log.warn("rpcFuture is null, logId={}", response.getLogId());
        }
    }

}
