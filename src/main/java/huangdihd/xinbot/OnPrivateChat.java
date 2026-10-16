package huangdihd.xinbot;

import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.joml.Vector3d;
import xin.bbtt.Block.BlockState;
import xin.bbtt.MovementSync;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.mcbot.LangManager;
import xin.bbtt.mcbot.event.EventHandler;
import xin.bbtt.mcbot.event.Listener;
import xin.bbtt.mcbot.events.PrivateChatEvent;
import xin.bbtt.movement.Movement;

import xin.bbtt.movements.PathMovement;
import xin.bbtt.pathfinding.BuiltinMovementType;
import xin.bbtt.pathfinding.DStarLite;
import xin.bbtt.pathfinding.Node;
import xin.bbtt.pathfinding.PathStep;
import xin.bbtt.pathfinding.PathfindingContext;
import xin.bbtt.pathfinding.PathfindingContextBuilder;
import xin.bbtt.pathfinding.StandablePositionResolver;
import xin.bbtt.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicReference;

public class OnPrivateChat implements Listener {
    private static final long ACTION_TIMEOUT_MS = 30_000L;
    private static final int MAX_PRIVATE_MESSAGE_LENGTH = 100;
    private static final AtomicReference<BackAction> BACK_ACTION = new AtomicReference<>();

    @EventHandler
    public void onBack(PrivateChatEvent event) {
        String message = event.getMessage();
        if (message == null || message.isBlank()) {
            return;
        }
        String[] args = message.trim().split("\\s+");

        String senderName = event.getSender().getName();
        if (BackToTheBase.GAME_COMMAND_PREFIX.equalsIgnoreCase(args[0])
                || BackToTheBase.GAME_COMMAND_ALIAS_PREFIX.equalsIgnoreCase(args[0])) {
            handleAdminCommand(senderName, args);
            return;
        }
        if (!"back".equalsIgnoreCase(args[0])) return;

        BackToTheBase.INSTANCE.reloadConfig();

        String number = parseRequestedNumber(senderName, args);
        if (number == null) return;

        PlayerBaseConfig config = BackToTheBase.INSTANCE.getPlayerConfigs().get(senderName);
        if (config == null) {
            BackToTheBase.INSTANCE.getLogger().warn("Ignoring back command from {} because the player is not configured.", senderName);
            return;
        }

        ButtonLocation location = config.getLocation(number);
        if (location == null) {
            BackToTheBase.INSTANCE.getLogger().warn("No pearl button location number {} configured for {}.", number, senderName);
            sendPrivate(senderName, LangManager.get("backtothebase.location.not_configured", number));
            return;
        }

        if (!Bot.INSTANCE.getPluginManager().isPluginEnabled("MovementSync")) {
            BackToTheBase.INSTANCE.getLogger().warn("MovementSync is not enabled. Cannot run back command.");
            sendPrivate(senderName, LangManager.get("backtothebase.movementsync.disabled"));
            return;
        }
        if (!(Bot.INSTANCE.getPluginManager().getPlugin("MovementSync").getPlugin() instanceof MovementSync movementSync)) {
            BackToTheBase.INSTANCE.getLogger().error("MovementSync plugin instance is invalid. Cannot run back command.");
            sendPrivate(senderName, LangManager.get("backtothebase.movementsync.invalid"));
            return;
        }
        BackAction action = acquireBackAction(movementSync);
        if (action == null) {
            sendPrivate(senderName, LangManager.get("backtothebase.movementsync.busy"));
            return;
        }

        boolean handedOff = false;
        try {
            BackToTheBase.INSTANCE.getLogger().info("BackToTheBase command from {} selected location number {}.", senderName, number);
            handedOff = queueButtonAction(action, senderName, location, BackToTheBase.INSTANCE.getBaseConfig().getReturnConfig());
            sendPrivate(senderName, LangManager.get(handedOff ? "backtothebase.back.started" : "backtothebase.back.failed", number));
        } catch (RuntimeException e) {
            BackToTheBase.INSTANCE.getLogger().error("Failed to start BackToTheBase action for {}.", senderName, e);
        } finally {
            if (!handedOff) cancelBackAction(action);
        }
    }

    private void handleAdminCommand(String senderName, String[] args) {
        BackToTheBase.INSTANCE.reloadConfig();

        PlayerBaseConfig.AdminConfig admin = BackToTheBase.INSTANCE.getBaseConfig().getAdmin();
        if (!admin.isEnabled()) {
            BackToTheBase.INSTANCE.getLogger().warn("Ignoring in-game admin command from {} because in-game admin commands are disabled.", senderName);
            return;
        }
        if (admin.getPlayers() == null || !admin.getPlayers().contains(senderName)) {
            BackToTheBase.INSTANCE.getLogger().warn("Ignoring in-game admin command from {} because the sender is not an admin.", senderName);
            return;
        }
        String[] commandArgs = new String[args.length - 1];
        System.arraycopy(args, 1, commandArgs, 0, commandArgs.length);
        for (String line : BackToTheBase.INSTANCE.handleManagementCommand(senderName, false, normalizeAdminPrefix(args[0]), commandArgs)) {
            sendPrivate(senderName, line);
        }
    }

    private String normalizeAdminPrefix(String prefix) {
        return BackToTheBase.GAME_COMMAND_ALIAS_PREFIX.equalsIgnoreCase(prefix)
                ? BackToTheBase.GAME_COMMAND_ALIAS_PREFIX
                : BackToTheBase.GAME_COMMAND_PREFIX;
    }

    private void sendPrivate(String playerName, String message) {
        for (String part : splitMessage(message)) {
            Bot.INSTANCE.sendCommand("msg " + playerName + " " + part);
        }
    }

    static List<String> splitMessage(String message) {
        if (message.length() <= MAX_PRIVATE_MESSAGE_LENGTH) {
            return List.of(message);
        }

        List<String> parts = new ArrayList<>();
        String remaining = message;
        while (remaining.length() > MAX_PRIVATE_MESSAGE_LENGTH) {
            int split = MAX_PRIVATE_MESSAGE_LENGTH;
            while (split > 0 && !Character.isWhitespace(remaining.charAt(split))) {
                split--;
            }
            if (split == 0) {
                split = MAX_PRIVATE_MESSAGE_LENGTH;
            }
            parts.add(remaining.substring(0, split));
            remaining = remaining.substring(split).stripLeading();
        }
        if (!remaining.isEmpty()) {
            parts.add(remaining);
        }
        return parts;
    }

    private static BackAction acquireBackAction(MovementSync movementSync) {
        long now = System.currentTimeMillis();
        BackAction previous = BACK_ACTION.get();
        if (previous != null) {
            if ((previous.queued && (!isCurrentAction(previous) || movementsEmpty(previous.movementSync)))
                    || now - previous.startedAt > ACTION_TIMEOUT_MS) {
                cancelBackAction(previous);
            } else {
                return null;
            }
        }
        if (movementSync != MovementSync.INSTANCE || movementBusy(movementSync)) return null;
        BackAction action = new BackAction(movementSync, now);
        return BACK_ACTION.compareAndSet(null, action) ? action : null;
    }

    private static boolean movementsEmpty(MovementSync movementSync) {
        return movementSync.getMovementController().getCurrentMovement() == null
                && movementSync.getMovementController().getMovements().isEmpty()
                && movementSync.getMovementController().getCurrentMovement() == null;
    }

    private static boolean movementBusy(MovementSync movementSync) {
        return movementSync.getMovementController().isPaused()
                || movementSync.getFollowTargetId() != -1 || !movementsEmpty(movementSync);
    }

    private static boolean isCurrentAction(BackAction action) {
        return BACK_ACTION.get() == action && action.movementSync == MovementSync.INSTANCE
                && action.movementSync.isNavigationRequestCurrent(action.generation);
    }

    private static void releaseBackAction(BackAction action) {
        BACK_ACTION.compareAndSet(action, null);
    }

    // Called outside movement callbacks; their init/onStop run under the controller lock.
    static void cancelActiveAction() {
        BackAction action = BACK_ACTION.get();
        if (action != null) cancelBackAction(action);
    }

    private static void cancelBackAction(BackAction action) {
        long generation;
        do {
            generation = action.generation;
            if (action.movementSync.runIfNavigationRequestCurrent(generation, () -> {
                releaseBackAction(action);
                // Wait for an in-flight init before clearing any goal it may have published.
                action.movementSync.getMovementController().getCurrentMovement();
                if (action.goal != null && action.goal.equals(action.movementSync.getActiveGoal())
                        && action.movementSync.getFollowTargetId() == -1) {
                    action.movementSync.setActiveGoal(null);
                }
            })) return;
            // Initial publication may have reserved a new generation while cancellation waited.
        } while (generation != action.generation);
        releaseBackAction(action); // A superseded generation cannot send another click.
    }

    private static boolean queueMovement(BackAction action, Movement movement) {
        if (!isCurrentAction(action)) return false;
        action.movementSync.getMovementController().addMovement(movement);
        return true;
    }

    private static class BackAction {
        private final MovementSync movementSync;
        private final long startedAt;
        private volatile long generation;
        private volatile boolean queued;
        private volatile org.joml.Vector3i goal;

        private BackAction(MovementSync movementSync, long startedAt) {
            this.movementSync = movementSync;
            this.startedAt = startedAt;
            this.generation = movementSync.getNavigationGeneration();
        }
    }

    private String parseRequestedNumber(String senderName, String[] args) {
        if (args.length == 1) {
            return "1";
        }
        if (args.length != 2 || !isPositiveInteger(args[1])) {
            BackToTheBase.INSTANCE.getLogger().warn("Invalid back command from {}. Use back or back <positive number>.", senderName);
            return null;
        }
        return args[1];
    }

    private boolean isPositiveInteger(String value) {
        return value != null && value.matches("[1-9][0-9]*");
    }

    private boolean queueButtonAction(BackAction action, String playerName, ButtonLocation location, PlayerBaseConfig.ReturnConfig returnConfig) {
        MovementSync movementSync = action.movementSync;
        org.cloudburstmc.math.vector.Vector3i positionInt = location.toVector3i();
        World world = movementSync.getWorld();
        ButtonTarget target = getButtonTarget(world, positionInt);
        if (target == null) {
            return false;
        }

        Vector3d currentPos = movementSync.position.get();
        Node start = new Node((int)Math.floor(currentPos.x), StandablePositionResolver.nodeY(currentPos.y), (int)Math.floor(currentPos.z));
        PathSelection selection = null;
        if (!canClickFrom(world, currentPos, target.hitPosition())) {
            selection = findReachableStandingPath(world, positionInt, target.hitPosition(), currentPos, start);
            if (selection == null) {
                BackToTheBase.INSTANCE.getLogger().error("Could not find a reachable safe block to stand on near the button.");
                return false;
            }
            BackToTheBase.INSTANCE.getLogger().info(
                    "Starting walk to pearl button location {} for {} at {} {} {}.",
                    location.getNumber(),
                    playerName,
                    selection.goal().x,
                    selection.goal().y,
                    selection.goal().z
            );
        }

        PathSelection selected = selection;
        boolean[] handedOff = {false};
        movementSync.runIfNavigationRequestCurrent(action.generation, () -> {
            if (!isCurrentAction(action) || movementBusy(movementSync)) return;
            // Reserve the idle navigator so an older, still-planning request cannot publish later.
            action.generation = movementSync.beginNavigationRequest();
            movementSync.setActiveGoal(null);
            if (selected != null && !queuePath(action, selected.goal(), selected.path())) return;
            handedOff[0] = queueMovement(action, new PrepareButtonClickMovement(action, playerName, location, returnConfig))
                    && isCurrentAction(action);
            action.queued = handedOff[0];
        });
        return handedOff[0];
    }

    private static ButtonTarget getButtonTarget(World world, org.cloudburstmc.math.vector.Vector3i positionInt) {
        Vector3d blockCenter = new Vector3d(positionInt.getX(), positionInt.getY(), positionInt.getZ()).add(new Vector3d(.5, .5, .5));
        BlockState blockState = world.getBlockStateAt(blockCenter);
        if (blockState == null || blockState.blockName() == null || !blockState.blockName().endsWith("button")) {
            BackToTheBase.INSTANCE.getLogger().error("Target block at {} {} {} is not a button.", positionInt.getX(), positionInt.getY(), positionInt.getZ());
            return null;
        }

        Direction clickDirection = getClickDirection(blockState);
        if (clickDirection == null) {
            return null;
        }
        xin.bbtt.world.Direction msDirection = xin.bbtt.world.Direction.valueOf(clickDirection.name());
        Vector3d hitPosition = new Vector3d(blockCenter).add(msDirection.getVector(0.5));
        return new ButtonTarget(positionInt, clickDirection, hitPosition);
    }

    private static Direction getClickDirection(BlockState blockState) {
        if (blockState == null || blockState.properties() == null) {
            BackToTheBase.INSTANCE.getLogger().error("Button block state is missing properties.");
            return null;
        }

        String face = blockState.getProperty("face");
        if (face != null) {
            face = face.toLowerCase();
        }
        if ("floor".equals(face)) {
            return Direction.UP;
        }
        if ("ceiling".equals(face)) {
            return Direction.DOWN;
        }

        String facing = blockState.getProperty("facing");
        if (facing == null) {
            BackToTheBase.INSTANCE.getLogger().error("Button block state is missing facing property.");
            return null;
        }
        try {
            return Direction.valueOf(facing.toUpperCase());
        } catch (IllegalArgumentException e) {
            BackToTheBase.INSTANCE.getLogger().error("Button block state has invalid facing property {}.", facing);
            return null;
        }
    }

    private static boolean canClickFrom(World world, Vector3d currentPos, Vector3d hitPosition) {
        double eyeY = currentPos.y + 1.62;
        Vector3d eyePos = new Vector3d(currentPos.x, eyeY, currentPos.z);
        double currentDistToButtonSq = Math.pow(currentPos.x - hitPosition.x, 2) +
                Math.pow(eyeY - hitPosition.y, 2) +
                Math.pow(currentPos.z - hitPosition.z, 2);
        return currentDistToButtonSq <= 16.0 && world.canSee(eyePos, hitPosition);
    }

    private static PathSelection findReachableStandingPath(World world, org.cloudburstmc.math.vector.Vector3i positionInt, Vector3d hitPosition, Vector3d currentPos, Node start) {
        List<StandingCandidate> candidates = findStandingCandidates(world, positionInt, hitPosition, currentPos);
        for (StandingCandidate candidate : candidates) {
            List<PathStep> path = findPath(start, candidate.node());
            if (path != null && candidate.node().equals(path.get(path.size() - 1).getNode())) {
                return new PathSelection(candidate.node(), path);
            }
        }
        return null;
    }

    private static List<StandingCandidate> findStandingCandidates(World world, org.cloudburstmc.math.vector.Vector3i positionInt, Vector3d hitPosition, Vector3d currentPos) {
        List<StandingCandidate> candidates = new ArrayList<>();
        for (int dy = -3; dy <= 3; dy++) {
            long candidateY = (long) positionInt.getY() + dy;
            if (candidateY < Integer.MIN_VALUE || candidateY > Integer.MAX_VALUE) continue;
            int y = (int) candidateY;
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    int distToButtonSq = dx * dx + dy * dy + dz * dz;
                    if (distToButtonSq < 1 || distToButtonSq > 12) continue;
                    int cx = positionInt.getX() + dx;
                    int cz = positionInt.getZ() + dz;

                    OptionalDouble feetY = StandablePositionResolver.feetY(world, cx, y, cz);
                    if (feetY.isEmpty()) continue;

                    Vector3d standPos = new Vector3d(cx + 0.5, feetY.getAsDouble(), cz + 0.5);
                    if (canClickFrom(world, standPos, hitPosition)) {
                        double distToBotSq = standPos.distanceSquared(currentPos);
                        candidates.add(new StandingCandidate(new Node(cx, y, cz), distToBotSq));
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(StandingCandidate::distToBotSq));
        return candidates;
    }

    private static boolean pathTo(BackAction action, Node start, Node goal) {
        List<PathStep> path = findPath(start, goal);

        if (path != null) {
            return queuePath(action, goal, path);
        }
        BackToTheBase.INSTANCE.getLogger().error("Could not find a path to target {} {} {}.", goal.x, goal.y, goal.z);
        return false;
    }

    private static List<PathStep> findPath(Node start, Node goal) {
        if (start.equals(goal)) {
            return List.of(new PathStep(start, BuiltinMovementType.WALK));
        }
        DStarLite pathfinder = new DStarLite(start, goal, createPathfindingContext());
        List<PathStep> path = pathfinder.findPath(5000);
        if (path != null && path.size() > 1) {
            return path;
        }
        return null;
    }

    private static boolean queuePath(BackAction action, Node goal, List<PathStep> path) {
        if (!isCurrentAction(action) || path == null || path.isEmpty()) return false;
        action.goal = new org.joml.Vector3i(goal.x, goal.y, goal.z);
        action.movementSync.setActiveGoal(action.goal);
        return queueMovement(action, new PathMovement(path) {
            @Override
            public void onTick() {
                if (isCurrentAction(action)) super.onTick();
                else setFinished(true);
            }

            @Override
            public void onStop() {
                super.onStop();
                if (!isCurrentAction(action) && action.movementSync.isNavigationRequestCurrent(action.generation)
                        && action.goal.equals(action.movementSync.getActiveGoal())) {
                    action.movementSync.setActiveGoal(null);
                }
            }
        });
    }

    private static PathfindingContext createPathfindingContext() {
        return new PathfindingContextBuilder(MovementSync.INSTANCE.getWorld())
                .addWalk()
                .build();
    }

    private record ButtonTarget(
            org.cloudburstmc.math.vector.Vector3i position,
            Direction direction,
            Vector3d hitPosition
    ) {
    }

    private record StandingCandidate(Node node, double distToBotSq) {
    }

    private record PathSelection(Node goal, List<PathStep> path) {
    }

    private static class PrepareButtonClickMovement extends Movement {
        private final BackAction action;
        private final String playerName;
        private final ButtonLocation location;
        private final PlayerBaseConfig.ReturnConfig returnConfig;
        private boolean handedOff;

        private PrepareButtonClickMovement(BackAction action, String playerName, ButtonLocation location, PlayerBaseConfig.ReturnConfig returnConfig) {
            this.action = action;
            this.playerName = playerName;
            this.location = location;
            this.returnConfig = returnConfig;
        }

        @Override
        public void init() {
            try {
                if (!isCurrentAction(action)) return;
                ButtonTarget target = getButtonTarget(action.movementSync.getWorld(), location.toVector3i());
                if (target == null) {
                    return;
                }
                Vector3d currentPos = action.movementSync.position.get();
                if (!canClickFrom(action.movementSync.getWorld(), currentPos, target.hitPosition())) {
                    BackToTheBase.INSTANCE.getLogger().warn(
                            "Reached pearl button area for {}, but the button at {} {} {} is still not visible or is too far away.",
                            playerName,
                            location.getX(),
                            location.getY(),
                            location.getZ()
                    );
                    return;
                }

                if (!queueMovement(action, new UseItemOnMovement(
                        target.position(),
                        target.direction(),
                        target.hitPosition()
                ) {
                    @Override
                    public void onTick() {
                        if (!action.movementSync.runIfNavigationRequestCurrent(action.generation, () -> {
                            if (!isCurrentAction(action)) {
                                setFinished(true);
                                return;
                            }
                            try {
                                super.onTick();
                            } catch (RuntimeException e) {
                                releaseBackAction(action);
                                throw e;
                            }
                        })) setFinished(true);
                    }
                })) return;

                BackToTheBase.INSTANCE.getLogger().info("Looking at and clicking pearl button location {} for {}.", location.getNumber(), playerName);

                if (returnConfig.isEnabled()) {
                    if (!queueMovement(action, new WaitTicksMovement(3))) return;
                    handedOff = queueMovement(action, new ReturnAfterUseMovement(action, playerName, returnConfig.getLocation()));
                } else {
                    handedOff = queueMovement(action, new FinishBackActionMovement(action));
                }
            } catch (RuntimeException e) {
                BackToTheBase.INSTANCE.getLogger().error("Failed to prepare pearl button click for {}.", playerName, e);
            } finally {
                if (!handedOff) releaseBackAction(action);
                setFinished(true);
            }
        }

        @Override
        public void onTick() {
        }

        @Override
        public long getTime() {
            return 50;
        }

        @Override
        public void onStop() {
            if (!handedOff) {
                releaseBackAction(action);
            }
        }
    }

    private static class WaitTicksMovement extends Movement {
        private int remainingTicks;

        private WaitTicksMovement(int ticks) {
            this.remainingTicks = ticks;
        }

        @Override
        public void init() {
        }

        @Override
        public void onTick() {
            remainingTicks--;
            if (remainingTicks <= 0) {
                setFinished(true);
            }
        }

        @Override
        public long getTime() {
            return -1;
        }

        @Override
        public void onStop() {
        }
    }

    private static class ReturnAfterUseMovement extends Movement {
        private final BackAction action;
        private final String playerName;
        private final PlayerBaseConfig.ReturnLocation returnLocation;
        private boolean handedOff;

        private ReturnAfterUseMovement(BackAction action, String playerName, PlayerBaseConfig.ReturnLocation returnLocation) {
            this.action = action;
            this.playerName = playerName;
            this.returnLocation = returnLocation;
        }

        @Override
        public void init() {
            try {
                if (!isCurrentAction(action)) return;
                if (returnLocation == null) {
                    BackToTheBase.INSTANCE.getLogger().warn("return.enabled is true, but return.location is missing.");
                    handedOff = queueMovement(action, new FinishBackActionMovement(action));
                    return;
                }

                Node goal = new Node(returnLocation.getX(), returnLocation.getY(), returnLocation.getZ());
                Vector3d currentPos = action.movementSync.position.get();
                Node start = new Node((int)Math.floor(currentPos.x), StandablePositionResolver.nodeY(currentPos.y), (int)Math.floor(currentPos.z));
                BackToTheBase.INSTANCE.getLogger().info("Starting return to {} {} {} for {}.", goal.x, goal.y, goal.z, playerName);
                if (!pathTo(action, start, goal)) {
                    return;
                }
                handedOff = queueMovement(action, new FinishBackActionMovement(action));
            } catch (RuntimeException e) {
                BackToTheBase.INSTANCE.getLogger().error("Failed to prepare return movement for {}.", playerName, e);
            } finally {
                if (!handedOff) releaseBackAction(action);
                setFinished(true);
            }
        }

        @Override
        public void onTick() {
        }

        @Override
        public long getTime() {
            return 50;
        }

        @Override
        public void onStop() {
            if (!handedOff) {
                releaseBackAction(action);
            }
        }
    }

    private static class FinishBackActionMovement extends Movement {
        private final BackAction action;

        private FinishBackActionMovement(BackAction action) {
            this.action = action;
        }

        @Override
        public void init() {
            releaseBackAction(action);
            setFinished(true);
        }

        @Override
        public void onTick() {
        }

        @Override
        public long getTime() {
            return 50;
        }

        @Override
        public void onStop() {
            releaseBackAction(action);
        }
    }
}
