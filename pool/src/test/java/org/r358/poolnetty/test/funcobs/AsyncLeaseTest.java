/*
 * Copyright (c) 2014 R358 https://github.com/R358
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.r358.poolnetty.test.funcobs;

import org.r358.poolnetty.common.*;
import org.r358.poolnetty.common.exceptions.PoolProviderException;
import org.r358.poolnetty.pool.NettyConnectionPool;
import org.r358.poolnetty.pool.NettyConnectionPoolBuilder;
import org.r358.poolnetty.test.simpleserver.SimpleInboundHandler;
import org.r358.poolnetty.test.simpleserver.SimpleOutboundHandler;
import org.r358.poolnetty.test.simpleserver.SimpleServer;
import org.r358.poolnetty.test.simpleserver.SimpleServerListener;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import junit.framework.TestCase;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test Asynchronous behavior on single connection.
 * Uses single connection.
 */
@RunWith(JUnit4.class)
public class AsyncLeaseTest
{

    @Test
    public void testWithCallbackListener()
        throws Exception
    {
        final CountDownLatch startedLatch = new CountDownLatch(1);
        final CountDownLatch leaseRequestedLatch = new CountDownLatch(1);
        final CountDownLatch leaseGrantedLatch = new CountDownLatch(1);
        final CountDownLatch leaseYieldedLatch = new CountDownLatch(1);
        final CountDownLatch stopLatch = new CountDownLatch(1);
        final CountDownLatch connectionOpenedLatch = new CountDownLatch(1);
        final CountDownLatch connectionClosedLatch = new CountDownLatch(1);


        final AtomicReference<String> messageAtServer = new AtomicReference<>(); // I need to set the message into something!

        final List<Object> listOfUserObjectReports = new ArrayList<>();

        final String originalMessage = "The cat sat on the mat.";


        //
        // Pool listener.
        //
        PoolProviderListener ppl = new PoolProviderListener()
        {
            @Override
            public void started(PoolProvider provider)
            {
                startedLatch.countDown();
            }

            @Override
            public void stopped(PoolProvider provider)
            {
                stopLatch.countDown();
            }

            @Override
            public void leaseRequested(PoolProvider provider, int leaseTime, TimeUnit units, Object userObject)
            {
                leaseRequestedLatch.countDown();
                listOfUserObjectReports.add(userObject.toString() + ".request");
            }

            @Override
            public void leaseGranted(PoolProvider provider, Channel channel, Object userObject)
            {
                leaseGrantedLatch.countDown();
                listOfUserObjectReports.add(userObject.toString() + ".granted");
            }

            @Override
            public void leaseCanceled(PoolProvider provider, Object userObject)
            {

            }

            @Override
            public void leaseYield(PoolProvider provider, Channel channel, Object userObject)
            {
                leaseYieldedLatch.countDown();
                listOfUserObjectReports.add(userObject.toString() + ".yield");
            }

            @Override
            public void leaseExpired(PoolProvider provider, Channel channel, Object userObject)
            {

            }

            @Override
            public void connectionClosed(PoolProvider provider, Channel ctx)
            {
                connectionClosedLatch.countDown();
            }

            @Override
            public void connectionCreated(PoolProvider provider, Channel ctx, boolean immortal)
            {
                connectionOpenedLatch.countDown();
            }

            @Override
            public void ephemeralReaped(PoolProvider poolProvider, Channel channel)
            {
                // Not tested here..
            }
        };


        //
        // The simple server side for testing.
        //

        SimpleServer simpleServer = new SimpleServer("127.0.0.1", 1887, 10, new SimpleServerListener()
        {

            @Override
            public void newConnection(ChannelHandlerContext ctx)
            {

            }

            @Override
            public void newValue(ChannelHandlerContext ctx, String val)
            {
                messageAtServer.set(val);
                ctx.writeAndFlush(val);
            }
        });

        simpleServer.start();


        //
        // Build the pool.
        //

        NettyConnectionPoolBuilder ncb = new NettyConnectionPoolBuilder(1, 1, 1);


        final EventLoopGroup elg = new NioEventLoopGroup();


        //
        // Create the boot strap.
        //
        ncb.withBootstrapProvider(new BootstrapProvider()
        {
            @Override
            public Bootstrap createBootstrap(PoolProvider poolProvider)
            {
                Bootstrap bs = new Bootstrap();
                bs.group(elg);
                bs.channel(NioSocketChannel.class);
                bs.option(ChannelOption.SO_KEEPALIVE, true);
                bs.option(ChannelOption.AUTO_READ, true);
                return bs;
            }
        });


        //
        // Sets up the connection info and the channel initializer.
        //
        ncb.withConnectionInfoProvider(new ConnectionInfoProvider()
        {
            @Override
            public ConnectionInfo connectionInfo(PoolProvider poolProvider)
            {

                return new ConnectionInfo(new InetSocketAddress("127.0.0.1", 1887), null, new ChannelInitializer()
                {
                    @Override
                    protected void initChannel(Channel ch)
                        throws Exception
                    {
                        ch.pipeline().addLast("decode", new SimpleInboundHandler(10));
                        ch.pipeline().addLast("encode", new SimpleOutboundHandler(10));
                    }
                });


            }
        });


        //
        // Make the pool add listener and start.
        //
        NettyConnectionPool ncp = ncb.build();
        ncp.addListener(ppl);


        ncp.start(0, TimeUnit.SECONDS);

        TestCase.assertTrue("Opening connection..", connectionOpenedLatch.await(5, TimeUnit.SECONDS));
        TestCase.assertTrue("Not started..", startedLatch.await(5, TimeUnit.SECONDS));


        String userObject = "Foo!";


        LeasedChannel ctx = null;

        final CountDownLatch respLatch = new CountDownLatch(1);
        final AtomicReference<String> respValue = new AtomicReference<>();

        //
        // Call with callback / listener.
        //

        ncp.leaseAsync(10, TimeUnit.DAYS, userObject, new LeaseListener()
        {
            @Override
            public void leaseRequest(boolean success, LeasedChannel channel, Throwable th)
            {

                //
                // Remember that any mods you make the pipeline when you have leased the channel
                // Will impact the next lease holder.
                //

                channel.pipeline().addLast("_foo_", new ChannelInboundHandlerAdapter()
                {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg)
                        throws Exception
                    {

                        respValue.set(msg.toString());
                        respLatch.countDown();
                    }
                });


                // Send the message.
                channel.writeAndFlush(originalMessage);

                //
                // Did we get a response back from the server.
                //
                try
                {
                    TestCase.assertTrue("Echo from server.", respLatch.await(5, TimeUnit.SECONDS));
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }


                //
                // Clean it up as a matter of habit.
                //
                channel.pipeline().remove("_foo_");

                //
                // Yield lease.
                //
                try
                {
                    channel.yield();
                }
                catch (PoolProviderException e)
                {
                    e.printStackTrace();
                }

            }
        });

        TestCase.assertTrue("Lease not requested", leaseRequestedLatch.await(5, TimeUnit.SECONDS));
        TestCase.assertTrue("Lease not granted", leaseGrantedLatch.await(5, TimeUnit.SECONDS));


        TestCase.assertTrue("Lease not yielded", leaseYieldedLatch.await(5, TimeUnit.SECONDS));

        ncp.stop(false);

        TestCase.assertTrue("Connection Not Closed.", connectionClosedLatch.await(5, TimeUnit.SECONDS));


        TestCase.assertTrue("Not stopped.", stopLatch.await(5, TimeUnit.SECONDS));

        //
        // Check we got back what we sent etc.
        //
        TestCase.assertEquals(originalMessage, messageAtServer.get());
        TestCase.assertEquals(originalMessage, respValue.get());

        //
        // - Request lease, Lease granted , Lease yielded check order.
        //
        TestCase.assertEquals(3, listOfUserObjectReports.size()); // Should only be 3 reports.

        TestCase.assertEquals(userObject + ".request", listOfUserObjectReports.get(0));
        TestCase.assertEquals(userObject + ".granted", listOfUserObjectReports.get(1));
        TestCase.assertEquals(userObject + ".yield", listOfUserObjectReports.get(2));

        simpleServer.stop();
    }


    @Test
    public void testWithFuture()
        throws Exception
    {
        final CountDownLatch startedLatch = new CountDownLatch(1);
        final CountDownLatch leaseRequestedLatch = new CountDownLatch(1);
        final CountDownLatch leaseGrantedLatch = new CountDownLatch(1);
        final CountDownLatch leaseYieldedLatch = new CountDownLatch(1);
        final CountDownLatch stopLatch = new CountDownLatch(1);
        final CountDownLatch connectionOpenedLatch = new CountDownLatch(1);
        final CountDownLatch connectionClosedLatch = new CountDownLatch(1);


        final AtomicReference<String> messageAtServer = new AtomicReference<>(); // I need to set the message into something!

        final List<Object> listOfUserObjectReports = new ArrayList<>();

        final String originalMessage = "The cat sat on the mat.";


        //
        // Pool listener.
        //
        PoolProviderListener ppl = new PoolProviderListener()
        {
            @Override
            public void started(PoolProvider provider)
            {
                startedLatch.countDown();
            }

            @Override
            public void stopped(PoolProvider provider)
            {
                stopLatch.countDown();
            }

            @Override
            public void leaseRequested(PoolProvider provider, int leaseTime, TimeUnit units, Object userObject)
            {
                leaseRequestedLatch.countDown();
                listOfUserObjectReports.add(userObject.toString() + ".request");
            }

            @Override
            public void leaseGranted(PoolProvider provider, Channel channel, Object userObject)
            {
                leaseGrantedLatch.countDown();
                listOfUserObjectReports.add(userObject.toString() + ".granted");
            }

            @Override
            public void leaseCanceled(PoolProvider provider, Object userObject)
            {

            }

            @Override
            public void leaseYield(PoolProvider provider, Channel channel, Object userObject)
            {
                leaseYieldedLatch.countDown();
                listOfUserObjectReports.add(userObject.toString() + ".yield");
            }

            @Override
            public void leaseExpired(PoolProvider provider, Channel channel, Object userObject)
            {

            }

            @Override
            public void connectionClosed(PoolProvider provider, Channel ctx)
            {
                connectionClosedLatch.countDown();
            }

            @Override
            public void connectionCreated(PoolProvider provider, Channel ctx, boolean immortal)
            {
                connectionOpenedLatch.countDown();
            }

            @Override
            public void ephemeralReaped(PoolProvider poolProvider, Channel channel)
            {
                // Not tested here..
            }
        };


        //
        // The simple server side for testing.
        //

        SimpleServer simpleServer = new SimpleServer("127.0.0.1", 1887, 10, new SimpleServerListener()
        {

            @Override
            public void newConnection(ChannelHandlerContext ctx)
            {

            }

            @Override
            public void newValue(ChannelHandlerContext ctx, String val)
            {
                messageAtServer.set(val);
                ctx.writeAndFlush(val);
            }
        });

        simpleServer.start();


        //
        // Build the pool.
        //

        NettyConnectionPoolBuilder ncb = new NettyConnectionPoolBuilder(1, 1, 1);


        final EventLoopGroup elg = new NioEventLoopGroup();


        //
        // Create the boot strap.
        //
        ncb.withBootstrapProvider(new BootstrapProvider()
        {
            @Override
            public Bootstrap createBootstrap(PoolProvider poolProvider)
            {
                Bootstrap bs = new Bootstrap();
                bs.group(elg);
                bs.channel(NioSocketChannel.class);
                bs.option(ChannelOption.SO_KEEPALIVE, true);
                bs.option(ChannelOption.AUTO_READ, true);
                return bs;
            }
        });


        //
        // Sets up the connection info and the channel initializer.
        //
        ncb.withConnectionInfoProvider(new ConnectionInfoProvider()
        {
            @Override
            public ConnectionInfo connectionInfo(PoolProvider poolProvider)
            {

                return new ConnectionInfo(new InetSocketAddress("127.0.0.1", 1887), null, new ChannelInitializer()
                {
                    @Override
                    protected void initChannel(Channel ch)
                        throws Exception
                    {
                        ch.pipeline().addLast("decode", new SimpleInboundHandler(10));
                        ch.pipeline().addLast("encode", new SimpleOutboundHandler(10));
                    }
                });


            }
        });


        //
        // Make the pool add listener and start.
        //
        NettyConnectionPool ncp = ncb.build();
        ncp.addListener(ppl);


        ncp.start(0, TimeUnit.SECONDS);
        TestCase.assertTrue("Opening connection..", connectionOpenedLatch.await(5, TimeUnit.SECONDS));
        TestCase.assertTrue("Not started..", startedLatch.await(5, TimeUnit.SECONDS));


        String userObject = "Foo!";


        Future<LeasedChannel> ctxFuture = ncp.leaseAsync(10, TimeUnit.DAYS, userObject);

        LeasedChannel ctx = null;

        ctx = ctxFuture.get(5, TimeUnit.SECONDS);


        //
        // Lease a channel.
        //
        //   Channel ctx = ncp.lease(10, TimeUnit.DAYS, userObject);

        TestCase.assertTrue("Lease not requested", leaseRequestedLatch.await(5, TimeUnit.SECONDS));
        TestCase.assertTrue("Lease not granted", leaseGrantedLatch.await(5, TimeUnit.SECONDS));

        final CountDownLatch respLatch = new CountDownLatch(1);
        final AtomicReference<String> respValue = new AtomicReference<>();

        //
        // Remember that any mods you make the pipeline when you have leased the channel
        // Will impact the next lease holder.
        //

        ctx.pipeline().addLast("_foo_", new ChannelInboundHandlerAdapter()
        {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg)
                throws Exception
            {

                respValue.set(msg.toString());
                respLatch.countDown();
            }
        });


        // Send the message.
        ctx.writeAndFlush(originalMessage);

        //
        // Did we get a response back from the server.
        //
        TestCase.assertTrue("Echo from server.", respLatch.await(5, TimeUnit.SECONDS));


        //
        // Clean it up as a matter of habit.
        //
        ctx.pipeline().remove("_foo_");

        //
        // Yield lease.
        //
        ncp.yield(ctx);


        TestCase.assertTrue("Lease not yielded", leaseYieldedLatch.await(5, TimeUnit.SECONDS));

        ncp.stop(false);

        TestCase.assertTrue("Connection Not Closed.", connectionClosedLatch.await(5, TimeUnit.SECONDS));


        TestCase.assertTrue("Not stopped.", stopLatch.await(5, TimeUnit.SECONDS));

        //
        // Check we got back what we sent etc.
        //
        TestCase.assertEquals(originalMessage, messageAtServer.get());
        TestCase.assertEquals(originalMessage, respValue.get());


        //
        // - Request lease, Lease granted , Lease yielded check order.
        //
        TestCase.assertEquals(3, listOfUserObjectReports.size()); // Should only be 3 reports.

        TestCase.assertEquals(userObject + ".request", listOfUserObjectReports.get(0));
        TestCase.assertEquals(userObject + ".granted", listOfUserObjectReports.get(1));
        TestCase.assertEquals(userObject + ".yield", listOfUserObjectReports.get(2));

        simpleServer.stop();
    }
}
