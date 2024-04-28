package com.github.communitysourcedminecraft.lobby;

import com.github.communitysourcedminecraft.hosting.NATSConnection;
import com.github.communitysourcedminecraft.hosting.ServerInfo;
import com.github.communitysourcedminecraft.hosting.rpc.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.nats.client.JetStreamApiException;
import io.nats.client.api.KeyValueConfiguration;
import net.hollowcube.polar.PolarLoader;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.extras.velocity.VelocityProxy;
import net.minestom.server.instance.LightingChunk;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.client.play.ClientChatAckPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URISyntaxException;

public class Main {
	private static final Logger logger = LoggerFactory.getLogger(Main.class);
	private static final Gson gson = new GsonBuilder()
		.disableHtmlEscaping()
		.create();

	private static final Pos SPAWN = new Pos(0, 10, 0, 180f, 0f);

	public static void main(String[] args) throws IOException, URISyntaxException, InterruptedException, JetStreamApiException {
		var proxySecret = System.getenv("PROXY_SECRET");
		if (proxySecret != null) {
			logger.info("Enabling Velocity proxy support...");
			VelocityProxy.enable(proxySecret);
		}

		var info = ServerInfo.parse();
		var nats = NATSConnection.connectBlocking(info);

		var playerKVStream = info.kvGamemodeKey() + "_players";
		nats
			.getConnection()
			.keyValueManagement()
			.create(KeyValueConfiguration
				.builder()
				.name(playerKVStream)
				.build());
		var players = nats
			.getConnection()
			.keyValue(playerKVStream);

		nats.registerHandler(RPCType.START_INSTALL, reqData -> {
			var req = gson.fromJson(reqData, RPCStartInstall.Request.class);
			logger.info("Received START_INSTALL request: {}", req);

			// TODO: Implement installation logic

			var startInstallResponse = new RPCStartInstall.Response(Status.OK, "Hello from Java!");

			return new RPCResponse(RPCType.START_INSTALL, startInstallResponse);
		});

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
			var player = event.getPlayer();

			player.setRespawnPoint(SPAWN);

			var uuid = player
				.getUuid()
				.toString();
			var ip = getPlayerIP(player);

			logger.info("Player {} ({}) connected from {}", player.getUsername(), uuid, ip);

			try {
				players.put(uuid, info.podName());
			} catch (IOException | JetStreamApiException e) {
				throw new RuntimeException(e);
			}
		});
		globalEventHandler.addListener(PlayerSpawnEvent.class, event -> {
			var player = event.getPlayer();

			player.setGameMode(GameMode.ADVENTURE);

			var inv = player.getInventory();
			inv.setItemStack(22, ItemStack
				.builder(Material.IRON_SWORD)
				.amount(1)
				.displayName(Component
					.text("Arena")
					.decoration(TextDecoration.BOLD, true)
					.decoration(TextDecoration.ITALIC, false))
				.lore(Component
					.empty()
					.append(Component
						.text("Powered by ", TextColor.color(0xFFD700))
						.decoration(TextDecoration.ITALIC, false))
					.append(Component
						.text("Minestom/Arena", TextColor.color(0xFF0000), TextDecoration.BOLD)
						.decoration(TextDecoration.ITALIC, false)))
				.build());

			inv.addInventoryCondition((p, slot, clickType, result) -> {
				if (slot != 22) return;

				logger.info("Player {} ({}) tried to interact with slot {} using click type {}", p.getUsername(), p.getUuid(), slot, clickType);

				result.setCancel(true);

				nats
					.getConnection()
					.request(info.rpcTransfersSubject(), gson
						.toJson(new RPCRequest(RPCType.TRANSFER_PLAYER, gson.toJson(new RPCTransferPlayer.Request(p.getUuid(), info.podName(), "arena"))))
						.getBytes())
					.handle((message, throwable) -> {
						if (throwable != null) {
							logger.error("Error transferring player", throwable);
							return null;
						}

						var res = gson.fromJson(new String(message.getData()), RPCTransferPlayer.Response.class);
						logger.info("Received transfer response: {}", res);

						return null;
					});
			});
		});
		globalEventHandler.addListener(PlayerDisconnectEvent.class, event -> {
			var player = event.getPlayer();

			var uuid = player
				.getUuid()
				.toString();
			var ip = getPlayerIP(player);

			logger.info("Player {} ({}) from {} disconnected", player.getUsername(), uuid, ip);

			try {
				players.delete(uuid);
			} catch (IOException | JetStreamApiException e) {
				throw new RuntimeException(e);
			}
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

	private static String getPlayerIP(Player player) {
		return player
			.getPlayerConnection()
			.getRemoteAddress()
			.toString();
	}
}