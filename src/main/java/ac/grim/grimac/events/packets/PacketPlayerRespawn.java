package ac.grim.grimac.events.packets;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.enums.Pose;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerJoinGame;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRespawn;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateHealth;
import org.bukkit.GameMode;
import org.bukkit.util.Vector;

import java.util.List;

public class PacketPlayerRespawn extends PacketListenerAbstract {

    public PacketPlayerRespawn() {
        super(PacketListenerPriority.MONITOR);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.UPDATE_HEALTH) {
            WrapperPlayServerUpdateHealth health = new WrapperPlayServerUpdateHealth(event);

            GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
            if (player == null) return;

            List<Runnable> tasks = event.getPromisedTasks();
            tasks.add(player::sendTransaction);

            if (health.getHealth() <= 0) {
                player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get() + 1, () -> player.isDead = true);
            } else {
                player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get() + 1, () -> player.isDead = false);
            }
        }

        if (event.getPacketType() == PacketType.Play.Server.JOIN_GAME) {
            GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
            if (player == null) return;

            WrapperPlayServerJoinGame joinGame = new WrapperPlayServerJoinGame(event);

            // Does anyone know how to write NBT?
            NBTList<NBTCompound> list = (NBTList<NBTCompound>) ((NBTCompound) joinGame.getDimensionCodec().getTags().values().toArray()[0]).getTags().values().toArray()[1];

            player.compensatedWorld.dimensions = list;
            player.compensatedWorld.setDimension(joinGame.getDimension().getType().getName(), true);
            player.compensatedWorld.setDimension(joinGame.getDimension().getType().getName(), false);
        }

        if (event.getPacketType() == PacketType.Play.Server.RESPAWN) {
            WrapperPlayServerRespawn respawn = new WrapperPlayServerRespawn(event);

            GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getUser());
            if (player == null) return;

            List<Runnable> tasks = event.getPromisedTasks();
            tasks.add(player::sendTransaction);

            // Force the player to accept a teleport before respawning
            player.getSetbackTeleportUtil().hasAcceptedSpawnTeleport = false;

            player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get() + 1, () -> {
                player.isDead = false;
                player.isSneaking = false;
                player.pose = Pose.STANDING;
                player.clientVelocity = new Vector();
                player.gamemode = GameMode.valueOf(respawn.getGameMode().name());
                player.compensatedWorld.setDimension(respawn.getDimension().getType().getName(), false);
            });
            player.compensatedWorld.setDimension(respawn.getDimension().getType().getName(), true);
        }
    }
}
