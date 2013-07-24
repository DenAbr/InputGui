/**
 * 
 * This software is part of the InputGui
 * 
 * Copyright (c) 2013 Cybermaxke
 * 
 * InputGui is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or 
 * any later version.
 * 
 * InputGui is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with InputGui. If not, see <http://www.gnu.org/licenses/>.
 * 
 */
package me.cybermaxke.inputgui.plugin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import me.cybermaxke.inputgui.api.event.CommandBlockEditEvent;
import me.cybermaxke.inputgui.api.event.ItemRenameEvent;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CommandBlock;
import org.bukkit.entity.Player;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import com.comphenix.protocol.Packets.Server;
import com.comphenix.protocol.Packets.Client;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ConnectionSide;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;

public class InputGuiPacketAdapter extends PacketAdapter {
	private InputGuiPlugin plugin;

	public InputGuiPacketAdapter(InputGuiPlugin plugin) {
		super(plugin, ConnectionSide.BOTH, ListenerPriority.NORMAL,
				/**
				 * Packets from the server.
				 */
				Server.BLOCK_CHANGE,
				Server.CLOSE_WINDOW,
				Server.OPEN_WINDOW,
				Server.RESPAWN,

				/**
				 * Packets from the client.
				 */
				Client.CUSTOM_PAYLOAD,
				Client.CHAT,
				Client.ARM_ANIMATION,
				Client.PLACE,
				Client.WINDOW_CLICK,
				Client.USE_ENTITY,
				Client.BLOCK_DIG,
				Client.BLOCK_ITEM_SWITCH,
				Client.SET_CREATIVE_SLOT);
		ProtocolLibrary.getProtocolManager().addPacketListener(this);
		this.plugin = plugin;
	}

	@Override
	public void onPacketSending(PacketEvent e) {
		PacketContainer packet = e.getPacket();
		InputGuiPlayer player = this.plugin.getPlayer(e.getPlayer());

		/**
		 * If the gui is closed, return.
		 */
		if (!player.isGuiOpen()) {
			return;
		}

		/**
		 * Cancel block changes for the block until the gui closes.
		 */
		int id = e.getPacketID();
		if (id == Server.BLOCK_CHANGE) {
			int x = packet.getIntegers().read(0);
			int y = packet.getIntegers().read(1);
			int z = packet.getIntegers().read(2);
			int material = packet.getIntegers().read(3);

			Location l = player.getFakeBlockLocation();
			if (l.getBlockX() == x && l.getBlockY() == y && l.getBlockZ() == z && 
					material != Material.COMMAND.getId()) {
				e.setCancelled(true);
				return;
			}
		/**
		 * If this packets are send, the gui can't be open.
		 */
		} else if (player.isCheckingPackets() && (id == Server.CLOSE_WINDOW ||
				id == Server.OPEN_WINDOW)) {
			player.setCancelled();
		}
	}

	@Override
	public void onPacketReceiving(PacketEvent e) {
		PacketContainer packet = e.getPacket();

		final Player player = e.getPlayer();
		final InputGuiPlayer iplayer = this.plugin.getPlayer(player);

		int id = e.getPacketID();
		if (id == Client.CUSTOM_PAYLOAD) {
			String tag = packet.getStrings().read(0);
			byte[] data = packet.getByteArrays().read(0);

			/**
			 * This is the tag that is used by the command block.
			 */
			if (tag.equals("MC|AdvCdm")) {
				ByteArrayInputStream bis = new ByteArrayInputStream(data);
				DataInputStream dis = new DataInputStream(bis);

				try {
					/**
					 * Reading the coords of the fake block location.
					 */
					int x = dis.readInt();
					int y = dis.readInt();
					int z = dis.readInt();

					/**
					 * Reading the string.
					 */
					StringBuilder builder = new StringBuilder();

					short stringLength = dis.readShort();
					for (int i = 0; i < stringLength; i++) {
						builder.append(dis.readChar());
					}

					String string = builder.toString();

					/**
					 * We are using a custom input gui.
					 */
					if (iplayer.isGuiOpen()) {
						/**
						 * Match the two locations.
						 */
						Location l = iplayer.getFakeBlockLocation();
						if (l == null || l.getBlockX() != x || l.getBlockY() != y || 
								l.getBlockZ() != z) {
							iplayer.setCancelled();
							return;
						}

						e.setCancelled(true);
						iplayer.setConfirmed(string);
					/**
					 * We are changing a command block.
					 */
					} else {
						Block block = e.getPlayer().getWorld().getBlockAt(x, y, z);
						BlockState state = block.getState();

						if (state instanceof CommandBlock) {
							CommandBlock cblock = (CommandBlock) state;
							CommandBlockEditEvent event = new CommandBlockEditEvent(cblock,
									cblock.getCommand(), string);
							Bukkit.getPluginManager().callEvent(event);

							if (event.isCancelled()) {
								e.setCancelled(true);
								return;
							}

							String command = event.getNewCommand();

							ByteArrayOutputStream bos = new ByteArrayOutputStream();
							DataOutputStream dos = new DataOutputStream(bos);

							dos.writeInt(x);
							dos.writeInt(y);
							dos.writeInt(z);

							dos.writeShort(command.length());
							dos.writeChars(command);

							packet.getByteArrays().write(0, bos.toByteArray());

							dos.close();
							bos.close();
						}
					}

					dis.close();
					bis.close();
				} catch (IOException ex) {
					ex.printStackTrace();
				}
			/**
			 * This is the tag that is used by the anvil renaming.
			 */
			} else if (tag.equals("MC|ItemName")) {
				final InventoryView view = e.getPlayer().getOpenInventory();

				if (view != null && view.getTopInventory() instanceof AnvilInventory) {
					final AnvilInventory inv = (AnvilInventory) view.getTopInventory();

					ItemStack renamed = inv.getItem(0);
					if (renamed == null) {
						return;
					}

					ItemMeta meta = renamed.getItemMeta();

					String oldName = meta != null && meta.hasDisplayName() ? meta.getDisplayName() 
							: null;
					String newName = (data == null || data.length < 1) ? "" : new String(data);

					ItemRenameEvent event = new ItemRenameEvent(view, oldName, newName);
					Bukkit.getPluginManager().callEvent(event);

					e.getPlayer().sendMessage("DEBUG 3");

					if (event.isCancelled() || event.getNewName() == null) {
						packet.getByteArrays().write(0, oldName.getBytes());

						new BukkitRunnable() {

							@Override
							public void run() {
								ItemStack item = inv.getItem(0);

								if (item != null) {
									PacketContainer packet = InputGuiUtils.getSetSlotPacket(view, 
											0, item);
									try {
										ProtocolLibrary.getProtocolManager().sendServerPacket(
												player, packet);
									} catch (Exception e) {
										e.printStackTrace();
									}
								}
							}

						}.runTaskLater(this.plugin, 2L);

						return;
					}

					if (event.isResetted()) {
						newName = "";
					} else {
						newName = event.getNewName();
					}

					e.getPlayer().sendMessage("DEBUG 1");
					packet.getByteArrays().write(0, newName.getBytes());
				}
			}
		/**
		 * Close the gui once the player is doing something that can't happen when the gui open.
		 */
		} else if (iplayer.isGuiOpen() && iplayer.isCheckingPackets() && (id == Client.CHAT ||
				id == Client.ARM_ANIMATION ||
				id == Client.PLACE ||
				id == Client.WINDOW_CLICK ||
				id == Client.USE_ENTITY ||
				id == Client.BLOCK_DIG ||
				id == Client.BLOCK_ITEM_SWITCH ||
				id == Client.SET_CREATIVE_SLOT)) {
			iplayer.setCancelled();
		}
	}
}