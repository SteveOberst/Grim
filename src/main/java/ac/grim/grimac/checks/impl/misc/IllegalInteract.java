package ac.grim.grimac.checks.impl.misc;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketCheck;
import ac.grim.grimac.events.packets.CheckManagerListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.CollisionData;
import ac.grim.grimac.utils.collisions.HitboxData;
import ac.grim.grimac.utils.collisions.datatypes.CollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.HitData;
import ac.grim.grimac.utils.latency.CompensatedWorld;
import ac.grim.grimac.utils.nmsutil.Ray;
import ac.grim.grimac.utils.nmsutil.ReachUtils;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import lombok.NonNull;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@CheckData(name = "IllegalInteract")
public class IllegalInteract extends Check implements PacketCheck {

    private static final double LENIENCY = 0.05;

    public IllegalInteract(final GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            final Player bukkitPlayer = Bukkit.getPlayer(player.getUniqueId());
            // Should never be null, however, we return incase it is for whatever reason to avoid raising a NPE.
            if (bukkitPlayer == null) return;

            final WrapperPlayClientPlayerBlockPlacement packet = new WrapperPlayClientPlayerBlockPlacement(event);
            final CompensatedWorld world = player.compensatedWorld;
            final Vector3i blockPos = packet.getBlockPosition();
            final double eyeHeight = player.getEyeHeight();
            final Vector3d eyePos = new Vector3d(player.x, player.y + eyeHeight, player.z);
            // return if player is interacting with the block his head is stuck in
            //TODO: instead of returning, check if interacted block is the block in the players head
            if (isBlockInHead(blockPos, eyePos.getX(), eyePos.getY(), eyePos.getZ())) return;

            final WrappedBlockState blockState = world.getWrappedBlockStateAt(blockPos);
            final Block block = bukkitPlayer.getWorld().getBlockAt(blockPos.x, blockPos.y, blockPos.z);
            final Material bukkitType = block.getType();
            // cancel? however, this might cause issues with intentional ghost/fake blocks
            if (!matches(bukkitType, blockState.getType())) {
                if(getConfig().getBooleanElse("IllegalInteract.cancel-on-block-mismatch", false)) {
                    event.setCancelled(true);
                }
                return;
            }
            if (!isInteractable(bukkitType)) return;

            final InteractionHand hand = packet.getHand();
            final HitData hitData = CheckManagerListener.getNearestHitResult(player, getHeldItem(bukkitPlayer, hand), false);

            if (!didRayTraceHit(blockState, packet.getBlockPosition()) || hitData == null) {
                // wow, he doesn't even bother looking at the block, we'll punish that
                flagAndAlert(String.format("block=%s", bukkitType));
                event.setCancelled(true);
                return;
            }

            final CollisionBox collisionBox = HitboxData.getBlockHitboxNonOverride(player, player.getClientVersion(), blockState, blockPos.x, blockPos.y, blockPos.z);
            final Vector hitVec = hitData.getBlockHitLocation();
            // create a small box at the hitVec
            final SimpleCollisionBox intersectionCollisionBox = new SimpleCollisionBox(
                    hitVec.getX() - LENIENCY, hitVec.getY() - LENIENCY, hitVec.getZ() - LENIENCY,
                    hitVec.getX() + LENIENCY, hitVec.getY() + LENIENCY, hitVec.getZ() + LENIENCY
            );

            if (!collisionBox.isCollided(intersectionCollisionBox)) {
                flagAndAlert(String.format("block=%s", bukkitType));
                event.setCancelled(true);
            }
        }
    }

    private boolean didRayTraceHit(final WrappedBlockState blockState, final Vector3i blockPos) {
        List<Vector3f> possibleLookDirs = new ArrayList<>(Arrays.asList(
                new Vector3f(player.lastXRot, player.yRot, 0),
                new Vector3f(player.xRot, player.yRot, 0)
        ));

        // 1.9+ players could be a tick behind because we don't get skipped ticks
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9)) {
            possibleLookDirs.add(new Vector3f(player.lastXRot, player.lastYRot, 0));
        }

        // 1.7 players do not have any of these issues! They are always on the latest look vector
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_8)) {
            possibleLookDirs = Collections.singletonList(new Vector3f(player.xRot, player.yRot, 0));
        }

        for (final double d : player.getPossibleEyeHeights()) {
            for (final Vector3f lookDir : possibleLookDirs) {
                final Vector3d starting = new Vector3d(player.x, player.y + d, player.z);
                // xRot and yRot are a tick behind
                final Ray trace = new Ray(player, starting.getX(), starting.getY(), starting.getZ(), lookDir.getX(), lookDir.getY());
                //final CollisionBox collisionBox = HitboxData.getBlockHitbox(player, getHeldItem(bukkitPlayer, hand), player.getClientVersion(), blockState, (int) blockPos.getX(), (int) blockPos.getY(), (int) blockPos.getZ());
                final CollisionBox collisionBox = HitboxData.getBlockHitboxNonOverride(player, player.getClientVersion(), blockState, blockPos.getX(), blockPos.getY(), blockPos.getZ());
                // if the returned collision box is a simple collision box use it, otherwise default to a full block
                final SimpleCollisionBox blockBox = collisionBox instanceof SimpleCollisionBox
                        ? (SimpleCollisionBox) collisionBox
                        : new SimpleCollisionBox(blockPos.getX(), blockPos.getY(), blockPos.getZ(), blockPos.getX() + 1.d, blockPos.getY() + 1.d, blockPos.getZ() + 1.d);

                for (Vector vector : trace.traverse(5, 0.1)) {
                    if (ReachUtils.isVecInside(blockBox, vector)) return true;
                }
            }
        }

        return false;
    }

    private boolean matches(final Material bukkitType, final StateType stateType) {
        return StateTypes.getByName(bukkitType.toString()) == stateType;
    }

    public static boolean isBlockInHead(final @NonNull Vector3i loc, final double x, final double y, final double z) {
        return loc.getX() == Location.locToBlock(x) && loc.getY() == Location.locToBlock(y) && loc.getZ() == Location.locToBlock(z);
    }

    private StateType getHeldItem(final @NonNull Player player, final @NonNull InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND
                ? StateTypes.getByName(player.getInventory().getItemInMainHand().getType().toString())
                : StateTypes.getByName(player.getInventory().getItemInOffHand().getType().toString());
    }

    private boolean isInteractable(final @NonNull Material type) {
        // Immediately return false if bukkit tells us that the type is not interactable
        if (!type.isInteractable()) return false;

        // This check is done using string to support legacy MC versions too.
        String str = type.toString();
        return !(str.endsWith("_STAIRS") || str.endsWith("_FENCE")
                || str.equals("REDSTONE_ORE")
                || str.equals("REDSTONE_WIRE")
                || str.equals("PUMPKIN")
                || str.equals("MOVING_PISTON")
                // Exclude lever and buttons for now due to hitbox inconsistencies
                || str.endsWith("_BUTTON")
                || str.equals("LEVER")
        );
    }
}
