package com.github.communitysourcedminecraft.lobby;

import net.hollowcube.polar.PolarLoader;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.extras.velocity.VelocityProxy;
import net.minestom.server.instance.LightingChunk;
import net.minestom.server.network.packet.client.play.ClientChatAckPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

public class Main {
	static Logger logger = LoggerFactory.getLogger(Main.class);

	private static final Pos SPAWN = new Pos(0, 10, 0, 180f, 0f);

	public static void main(String[] args) throws IOException, URISyntaxException {
		var proxySecret = System.getenv("PROXY_SECRET");
		if (proxySecret != null) {
			logger.info("Enabling Velocity proxy support...");
			VelocityProxy.enable(proxySecret);
		}

		var minecraftServer = MinecraftServer.init();

		// WARN net.minestom.server.listener.manager.PacketListenerManager -- Packet class net.minestom.server.network.packet.client.play.ClientChatAckPacket does not have any default listener! (The issue comes from Minestom)
		MinecraftServer
			.getPacketListenerManager()
			.setPlayListener(ClientChatAckPacket.class, (player, packet) -> {});

		var instanceManager = MinecraftServer.getInstanceManager();
		var instanceContainer = instanceManager.createInstanceContainer();
		instanceContainer.setChunkSupplier(LightingChunk::new);

		var conn = (HttpsURLConnection) new URI("https://s3.devminer.xyz/csmc/lobby.polar")
			.toURL()
			.openConnection();
		instanceContainer.setChunkLoader(new PolarLoader(conn.getInputStream()));

		var globalEventHandler = MinecraftServer.getGlobalEventHandler();
		globalEventHandler.addListener(AsyncPlayerConfigurationEvent.class, event -> {
			event.setSpawningInstance(instanceContainer);
			event
				.getPlayer()
				.setRespawnPoint(SPAWN);
		});
		globalEventHandler.addListener(PlayerMoveEvent.class, event -> {
			if (event
				.getNewPosition()
				.blockY() > 0) return;

			event
				.getPlayer()
				.teleport(SPAWN);
		});

		minecraftServer.start("0.0.0.0", 25565);
	}
}