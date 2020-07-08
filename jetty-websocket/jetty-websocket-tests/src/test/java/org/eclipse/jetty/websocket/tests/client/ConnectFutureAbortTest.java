//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.tests.client;

import java.util.EnumSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.servlet.DispatcherType;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.UpgradeException;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.client.io.UpgradeListener;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.WebSocketSessionListener;
import org.eclipse.jetty.websocket.server.NativeWebSocketConfiguration;
import org.eclipse.jetty.websocket.server.NativeWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter;
import org.eclipse.jetty.websocket.tests.CloseTrackingEndpoint;
import org.eclipse.jetty.websocket.tests.EventSocket;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConnectFutureAbortTest
{
    private Server server;
    private WebSocketClient client;

    @FunctionalInterface
    public interface Configuration
    {
        void configure(NativeWebSocketConfiguration configuration);
    }

    public void start(Configuration configuration) throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        server.setHandler(contextHandler);

        NativeWebSocketServletContainerInitializer.configure(contextHandler, (context, container) ->
            configuration.configure(container));
        contextHandler.addFilter(WebSocketUpgradeFilter.class, "/", EnumSet.of(DispatcherType.REQUEST));
        server.start();

        client = new WebSocketClient();
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        if (client != null)
            client.stop();
        if (server != null)
            server.stop();
    }

    @Test
    public void testAbortDuringCreator() throws Exception
    {
        final CountDownLatch enteredCreator = new CountDownLatch(1);
        final CountDownLatch exitCreator = new CountDownLatch(1);
        start(c ->
        {
            c.addMapping("/", (req, res) ->
            {
                try
                {
                    enteredCreator.countDown();
                    exitCreator.await();
                    return new EventSocket.EchoSocket();
                }
                catch (InterruptedException e)
                {
                    throw new IllegalStateException(e);
                }
            });
        });

        CloseTrackingEndpoint clientSocket = new CloseTrackingEndpoint();
        Future<Session> connect = client.connect(clientSocket, WSURI.toWebsocket(server.getURI()));

        // Cancel the future once we have entered the servers WebSocketCreator (after upgrade request is sent).
        assertTrue(enteredCreator.await(5, TimeUnit.SECONDS));
        assertTrue(connect.cancel(true));
        assertThrows(CancellationException.class, () -> connect.get(5, TimeUnit.SECONDS));
        exitCreator.countDown();
        assertFalse(clientSocket.openLatch.await(2, TimeUnit.SECONDS));

        // Strange that onError() is called but onOpen() is never called. (This would not be the case in Jetty-10).
        assertThat(clientSocket.error.get(), instanceOf(UpgradeException.class));
    }

    @Test
    public void testAbortSessionOnCreated() throws Exception
    {
        start(c -> c.addMapping("/", EventSocket.EchoSocket.class));

        final CountDownLatch enteredListener = new CountDownLatch(1);
        final CountDownLatch exitListener = new CountDownLatch(1);
        client.addSessionListener(new WebSocketSessionListener()
        {
            @Override
            public void onSessionCreated(WebSocketSession session)
            {
                try
                {
                    enteredListener.countDown();
                    exitListener.await();
                }
                catch (InterruptedException e)
                {
                    throw new IllegalStateException(e);
                }
            }
        });

        CloseTrackingEndpoint clientSocket = new CloseTrackingEndpoint();
        Future<Session> connect = client.connect(clientSocket, WSURI.toWebsocket(server.getURI()));

        // Abort when session is created, this is before future has been added to session and before the connection upgrade.
        assertTrue(enteredListener.await(5, TimeUnit.SECONDS));
        assertTrue(connect.cancel(true));
        assertThrows(CancellationException.class, () -> connect.get(5, TimeUnit.SECONDS));
        exitListener.countDown();
        assertFalse(clientSocket.openLatch.await(2, TimeUnit.SECONDS));

        // Strange that onError() is called but onOpen() is never called. (This would not be the case in Jetty-10).
        assertThat(clientSocket.error.get(), instanceOf(CancellationException.class));
    }

    @Test
    public void testAbortInHandshakeResponse() throws Exception
    {
        start(c -> c.addMapping("/", EventSocket.EchoSocket.class));

        final CountDownLatch enteredListener = new CountDownLatch(1);
        final CountDownLatch exitListener = new CountDownLatch(1);
        UpgradeListener upgradeListener = new AbstractUpgradeListener()
        {
            @Override
            public void onHandshakeResponse(UpgradeResponse response)
            {
                try
                {
                    enteredListener.countDown();
                    exitListener.await();
                }
                catch (InterruptedException e)
                {
                    throw new IllegalStateException(e);
                }
            }
        };

        CloseTrackingEndpoint clientSocket = new CloseTrackingEndpoint();
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        Future<Session> connect = client.connect(clientSocket, WSURI.toWebsocket(server.getURI()), upgradeRequest, upgradeListener);

        // Abort after after handshake response, which is before connection upgrade, but after future has been set on session.
        assertTrue(enteredListener.await(5, TimeUnit.SECONDS));
        assertTrue(connect.cancel(true));
        assertThrows(CancellationException.class, () -> connect.get(5, TimeUnit.SECONDS));
        exitListener.countDown();
        assertFalse(clientSocket.openLatch.await(2, TimeUnit.SECONDS));

        // Strange that onError() is called but onOpen() is never called. (This would not be the case in Jetty-10).
        assertThat(clientSocket.error.get(), instanceOf(CancellationException.class));
    }

    @Test
    public void testAbortOnOpened() throws Exception
    {
        start(c -> c.addMapping("/", EventSocket.EchoSocket.class));

        final CountDownLatch exitOnOpen = new CountDownLatch(1);
        CloseTrackingEndpoint clientSocket = new CloseTrackingEndpoint()
        {
            @Override
            public void onWebSocketConnect(Session session)
            {
                try
                {
                    super.onWebSocketConnect(session);
                    exitOnOpen.await();
                }
                catch (InterruptedException e)
                {
                    throw new IllegalStateException(e);
                }
            }
        };

        // Abort during the call to onOpened. This is after future has been added to session,
        // and after connection has been upgraded, but before future completion.
        Future<Session> connect = client.connect(clientSocket, WSURI.toWebsocket(server.getURI()));
        assertTrue(clientSocket.openLatch.await(5, TimeUnit.SECONDS));
        assertTrue(connect.cancel(true));
        exitOnOpen.countDown();

        // We got an error on the WebSocket endpoint and an error from the future.
        assertTrue(clientSocket.errorLatch.await(5, TimeUnit.SECONDS));
        assertThrows(CancellationException.class, () -> connect.get(5, TimeUnit.SECONDS));
    }

    @Test
    public void testAbortAfterCompletion() throws Exception
    {
        start(c -> c.addMapping("/", EventSocket.EchoSocket.class));

        CloseTrackingEndpoint clientSocket = new CloseTrackingEndpoint();
        Future<Session> connect = client.connect(clientSocket, WSURI.toWebsocket(server.getURI()));
        Session session = connect.get(5, TimeUnit.SECONDS);

        // If we can send and receive messages the future has been completed.
        assertTrue(clientSocket.openLatch.await(5, TimeUnit.SECONDS));
        clientSocket.getSession().getRemote().sendString("hello");
        assertThat(clientSocket.messageQueue.poll(5, TimeUnit.SECONDS), Matchers.is("hello"));

        // After it has been completed we should not get any errors from cancelling it.
        assertFalse(connect.cancel(true));
        assertThat(connect.get(5, TimeUnit.SECONDS), instanceOf(Session.class));
        assertFalse(clientSocket.closeLatch.await(2, TimeUnit.SECONDS));
        assertNull(clientSocket.error.get());

        // Close the session properly.
        session.close();
        assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientSocket.closeCode, is(StatusCode.NORMAL));
    }

    public abstract static class AbstractUpgradeListener implements UpgradeListener
    {
        @Override
        public void onHandshakeRequest(UpgradeRequest request)
        {
        }

        @Override
        public void onHandshakeResponse(UpgradeResponse response)
        {
        }
    }
}