package com.mario.gateway.socket.tcp;

import java.io.IOException;

import com.mario.entity.message.Message;
import com.mario.entity.message.impl.BaseSocketMessage;
import com.mario.gateway.socket.BaseSocketGateway;
import com.mario.gateway.socket.SocketMessageType;
import com.mario.gateway.socket.SocketReceiver;
import com.mario.gateway.socket.SocketSession;
import com.mario.worker.MessageEventFactory;
import com.nhb.messaging.socket.netty.codec.MsgpackDecoder;
import com.nhb.messaging.socket.netty.codec.MsgpackEncoder;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

public class NettyTCPSocketGateway extends BaseSocketGateway implements SocketReceiver {

	protected ChannelFuture channelFuture;
	protected EventLoopGroup bossGroup;
	protected EventLoopGroup workerGroup;
	protected ServerBootstrap bootstrap;

	@Override
	protected void _init() {
		if (this.getSessionManager() == null) {
			throw new RuntimeException("Session Manager cannot be null");
		}
	}

	@Override
	protected MessageEventFactory createEventFactory() {
		return new MessageEventFactory() {
			@Override
			public Message newInstance() {
				BaseSocketMessage result = new BaseSocketMessage();
				result.setGatewayName(getName());
				result.setGatewayType(getConfig().getType());
				return result;
			}
		};
	}

	@Override
	protected void __start() {
		bossGroup = new NioEventLoopGroup(3);
		workerGroup = new NioEventLoopGroup(4);

		bootstrap = new ServerBootstrap();
		bootstrap.group(bossGroup, workerGroup);
		bootstrap.channel(NioServerSocketChannel.class);
		bootstrap.handler(new LoggingHandler(LogLevel.DEBUG));

		bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
			@Override
			public void initChannel(SocketChannel ch) throws Exception {
				if (getConfig().isUseLengthPrepender()) {
					ch.pipeline().addLast("frameEncoder", new LengthFieldPrepender(4));
					ch.pipeline().addLast("frameDecoder", new LengthFieldBasedFrameDecoder(1048576, 0, 4, 0, 4));
				}
				ch.pipeline().addLast("msgpackEncoder", MsgpackEncoder.newInstance());
				ch.pipeline().addLast("msgpackDecoder", MsgpackDecoder.newInstance());
				ch.pipeline()
						.addLast(new NettyTCPSocketSession(ch.remoteAddress(),
								NettyTCPSocketGateway.this.getSessionManager(), NettyTCPSocketGateway.this,
								NettyTCPSocketGateway.this.getSerializer()));
			}
		});

		// Bind and start to accept incoming connections.
		channelFuture = getConfig().getHost() == null ? bootstrap.bind(getConfig().getPort())
				: bootstrap.bind(getConfig().getHost(), getConfig().getPort());

		try {
			if (channelFuture.await().isSuccess()) {
				getLogger().debug("Gateway " + this.getName() + " success binding to " + getConfig().getHost() + ":"
						+ getConfig().getPort());
			} else {
				throw new RuntimeException("Start gateway " + this.getName() + " error, unable to bind to "
						+ this.getConfig().getHost() + ":" + this.getConfig().getPort());
			}
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	protected void __stop() {
		Iterable<SocketSession> it = this.getSessionManager().iterator();
		for (SocketSession sess : it) {
			try {
				sess.close();
			} catch (IOException e) {
				getLogger().warn("Unable to close session: " + sess.getId());
			}
		}

		this.channelFuture.channel().close();
		this.workerGroup.shutdownGracefully();
		this.bossGroup.shutdownGracefully();
	}

	public void sessionOpened(String sessionId) {
		publishToWorkers(new Object[] { null, sessionId, SocketMessageType.OPENED });
	}

	public void sessionClosed(String sessionId) {
		publishToWorkers(new Object[] { null, sessionId, SocketMessageType.CLOSED });
	}

	@Override
	public void receive(String sessionId, Object data) {
		publishToWorkers(new Object[] { data, sessionId, SocketMessageType.MESSAGE });
	}

}
