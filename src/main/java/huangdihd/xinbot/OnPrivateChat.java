package huangdihd.xinbot;

import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.joml.Vector3d;
import xin.bbtt.Block.BlockState;
import xin.bbtt.MovementSync;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.mcbot.event.EventHandler;
import xin.bbtt.mcbot.event.Listener;
import xin.bbtt.mcbot.events.PrivateChatEvent;
import xin.bbtt.movement.Movement;

import xin.bbtt.movements.PathMovement;
import xin.bbtt.pathfinding.DStarLite;
import xin.bbtt.pathfinding.Node;
import xin.bbtt.pathfinding.PathfindingContext;
import xin.bbtt.pathfinding.PathfindingContextBuilder;
import xin.bbtt.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class OnPrivateChat implements Listener {
    private static final long ACTION_TIMEOUT_MS = 30_000L;
    private static final AtomicBoolean BACK_ACTION_RUNNING = new AtomicBoolean(false);
    private static volatile long backActionStartTime = 0L;

    @EventHandler
    public void onBack(PrivateChatEvent event) {
        String message = event.getMessage();
        if (message == null || message.isBlank()) {
            return;
        }
        String[] args = message.trim().split("\\s+");

        String senderName = event.getSender().getName();
        if ("@backtothebase".equalsIgnoreCase(args[0])) {
            handleAdminCommand(senderName, args);
            return;
        }
        if (!"back".equalsIgnoreCase(args[0])) return;

        BackToTheBase.INSTANCE.reloadConfig();

        String number = parseRequestedNumber(senderName, args);
        if (number == null) return;

        PlayerBaseConfig config = BackToTheBase.INSTANCE.getPlayerConfigs().get(senderName);
        if (config == null) {
            BackToTheBase.INSTANCE.getLogger().warn("[BackToTheBase] 已忽略 {} 的 back 命令，因为该玩家不在玩家列表中。", senderName);
            return;
        }

        ButtonLocation location = config.getLocation(number);
        if (location == null) {
            BackToTheBase.INSTANCE.getLogger().warn("No pearl button location number {} configured for {}.", number, senderName);
            sendPrivate(senderName, "[BackToTheBase] 珍珠坐标 " + number + " 未配置。");
            return;
        }

        if (!Bot.INSTANCE.getPluginManager().isPluginEnabled("MovementSync")) {
            BackToTheBase.INSTANCE.getLogger().warn("[BackToTheBase] MovementSync 未启用，无法执行 back 命令。");
            sendPrivate(senderName, "[BackToTheBase] MovementSync 未启用，无法执行 back 命令。");
            return;
        }
        if (!(Bot.INSTANCE.getPluginManager().getPlugin("MovementSync").getPlugin() instanceof MovementSync movementSync)) {
            BackToTheBase.INSTANCE.getLogger().error("[BackToTheBase] MovementSync 插件实例异常，无法执行 back 命令。");
            sendPrivate(senderName, "[BackToTheBase] MovementSync 插件实例异常，无法执行 back 命令。");
            return;
        }
        if (!acquireBackAction(senderName)) {
            BackToTheBase.INSTANCE.getLogger().warn("Ignoring back command from {} because a BackToTheBase action is already running.", senderName);
            sendPrivate(senderName, "[BackToTheBase] 已有拉珍珠任务正在运行。");
            return;
        }

        BackToTheBase.INSTANCE.getLogger().info("BackToTheBase command from {} selected location number {}.", senderName, number);
        sendPrivate(senderName, "[BackToTheBase] 正在拉动珍珠坐标 " + number + "。");
        if (!queueButtonAction(movementSync, senderName, location, BackToTheBase.INSTANCE.getBaseConfig().getReturnConfig())) {
            releaseBackAction();
        }
    }

    private void handleAdminCommand(String senderName, String[] args) {
        BackToTheBase.INSTANCE.reloadConfig();

        PlayerBaseConfig.AdminConfig admin = BackToTheBase.INSTANCE.getBaseConfig().getAdmin();
        if (!admin.isEnabled()) {
            BackToTheBase.INSTANCE.getLogger().warn("[BackToTheBase] 已忽略 {} 的游戏内管理命令，因为游戏内管理已关闭。", senderName);
            return;
        }
        if (admin.getPlayers() == null || !admin.getPlayers().contains(senderName)) {
            BackToTheBase.INSTANCE.getLogger().warn("[BackToTheBase] 已忽略 {} 的游戏内管理命令，因为该玩家不是管理员。", senderName);
            return;
        }
        String[] commandArgs = new String[args.length - 1];
        System.arraycopy(args, 1, commandArgs, 0, commandArgs.length);
        for (String line : BackToTheBase.INSTANCE.handleManagementCommand(senderName, false, commandArgs)) {
            sendPrivate(senderName, line);
        }
    }

    private void sendPrivate(String playerName, String message) {
        Bot.INSTANCE.sendCommand("msg " + playerName + " " + message);
    }

    private static boolean acquireBackAction(String senderName) {
        long now = System.currentTimeMillis();
        if (BACK_ACTION_RUNNING.get() && backActionStartTime > 0L && now - backActionStartTime > ACTION_TIMEOUT_MS) {
            BackToTheBase.INSTANCE.getLogger().warn("BackToTheBase action timed out. Releasing stale action lock before handling command from {}.", senderName);
            try {
                MovementSync.INSTANCE.getMovementController().cancelAll();
            } catch (Exception e) {
                BackToTheBase.INSTANCE.getLogger().warn("Failed to cancel stale MovementSync movements after BackToTheBase timeout.", e);
            }
            releaseBackAction();
        }
        if (!BACK_ACTION_RUNNING.compareAndSet(false, true)) {
            return false;
        }
        backActionStartTime = now;
        return true;
    }

    private static void releaseBackAction() {
        BACK_ACTION_RUNNING.set(false);
        backActionStartTime = 0L;
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

    private boolean queueButtonAction(MovementSync movementSync, String playerName, ButtonLocation location, PlayerBaseConfig.ReturnConfig returnConfig) {
        org.cloudburstmc.math.vector.Vector3i positionInt = location.toVector3i();
        World world = MovementSync.INSTANCE.getWorld();
        ButtonTarget target = getButtonTarget(world, positionInt);
        if (target == null) {
            return false;
        }

        Vector3d currentPos = MovementSync.INSTANCE.position.get();
        if (!canClickFrom(world, currentPos, target.hitPosition())) {
            Node start = new Node((int)Math.floor(currentPos.x), (int)Math.floor(currentPos.y), (int)Math.floor(currentPos.z));
            PathSelection selection = findReachableStandingPath(world, positionInt, target.hitPosition(), currentPos, start);
            if (selection == null) {
                BackToTheBase.INSTANCE.getLogger().error("Could not find a reachable safe block to stand on near the button.");
                return false;
            }
            queuePath(start, selection.goal(), selection.path());
            BackToTheBase.INSTANCE.getLogger().info(
                    "Starting walk to pearl button location {} for {} at {} {} {}.",
                    location.getNumber(),
                    playerName,
                    selection.goal().x,
                    selection.goal().y,
                    selection.goal().z
            );
        }

        movementSync.getMovementController().addMovement(new PrepareButtonClickMovement(playerName, location, returnConfig));
        return true;
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
            List<Node> path = findPath(start, candidate.node());
            if (path != null) {
                return new PathSelection(candidate.node(), path);
            }
        }
        return null;
    }

    private static List<StandingCandidate> findStandingCandidates(World world, org.cloudburstmc.math.vector.Vector3i positionInt, Vector3d hitPosition, Vector3d currentPos) {
        List<StandingCandidate> candidates = new ArrayList<>();
        int currentY = (int) Math.floor(currentPos.y);
        int minY = Math.min(currentY, positionInt.getY()) - 2;
        int maxY = Math.max(currentY, positionInt.getY()) + 2;

        for (int y = minY; y <= maxY; y++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    int cx = positionInt.getX() + dx;
                    int cz = positionInt.getZ() + dz;
                    int dy = y - positionInt.getY();

                    if (!isSolid(world, cx, y - 1, cz)) continue;
                    if (!isPassable(world, cx, y, cz)) continue;
                    if (!isPassable(world, cx, y + 1, cz)) continue;

                    double distToButtonSq = dx * dx + dy * dy + dz * dz;
                    if (distToButtonSq >= 1 && distToButtonSq <= 12) {
                        Vector3d standEyePos = new Vector3d(cx + 0.5, y + 1.62, cz + 0.5);
                        if (world.canSee(standEyePos, hitPosition)) {
                            double distToBotSq = Math.pow(cx - currentPos.x, 2) + Math.pow(y - currentPos.y, 2) + Math.pow(cz - currentPos.z, 2);
                            candidates.add(new StandingCandidate(new Node(cx, y, cz), distToBotSq));
                        }
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(StandingCandidate::distToBotSq));
        return candidates;
    }

    private static boolean isSolid(World world, int x, int y, int z) {
        BlockState blockState = world.getBlockStateAt(new Vector3d(x, y, z));
        return blockState != null && blockState.isSolid();
    }

    private static boolean isPassable(World world, int x, int y, int z) {
        BlockState blockState = world.getBlockStateAt(new Vector3d(x, y, z));
        return blockState != null && blockState.isPassable();
    }

    private static boolean pathTo(Node start, Node goal) {
        List<Node> path = findPath(start, goal);

        if (path != null) {
            queuePath(start, goal, path);
            return true;
        }
        BackToTheBase.INSTANCE.getLogger().error("Could not find a path to target {} {} {}.", goal.x, goal.y, goal.z);
        return false;
    }

    private static List<Node> findPath(Node start, Node goal) {
        if (start.equals(goal)) {
            return List.of(start);
        }
        DStarLite pathfinder = new DStarLite(start, goal, createPathfindingContext());
        List<Node> path = pathfinder.findPath(5000);
        if (path != null && path.size() > 1) {
            return path;
        }
        return null;
    }

    private static void queuePath(Node start, Node goal, List<Node> path) {
        MovementSync.INSTANCE.setActiveGoal(new org.joml.Vector3i(goal.x, goal.y, goal.z));
        if (!start.equals(goal) && path != null && path.size() > 1) {
            MovementSync.INSTANCE.getMovementController().addMovement(new PathMovement(path));
        }
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

    private record PathSelection(Node goal, List<Node> path) {
    }

    private static class PrepareButtonClickMovement extends Movement {
        private final String playerName;
        private final ButtonLocation location;
        private final PlayerBaseConfig.ReturnConfig returnConfig;
        private boolean handedOff;

        private PrepareButtonClickMovement(String playerName, ButtonLocation location, PlayerBaseConfig.ReturnConfig returnConfig) {
            this.playerName = playerName;
            this.location = location;
            this.returnConfig = returnConfig;
        }

        @Override
        public void init() {
            try {
                ButtonTarget target = getButtonTarget(MovementSync.INSTANCE.getWorld(), location.toVector3i());
                if (target == null) {
                    releaseBackAction();
                    setFinished(true);
                    return;
                }
                Vector3d currentPos = MovementSync.INSTANCE.position.get();
                if (!canClickFrom(MovementSync.INSTANCE.getWorld(), currentPos, target.hitPosition())) {
                    BackToTheBase.INSTANCE.getLogger().warn(
                            "Reached pearl button area for {}, but the button at {} {} {} is still not visible or is too far away.",
                            playerName,
                            location.getX(),
                            location.getY(),
                            location.getZ()
                    );
                    releaseBackAction();
                    setFinished(true);
                    return;
                }

                MovementSync.INSTANCE.getMovementController().addMovement(new UseItemOnMovement(
                        target.position(),
                        target.direction(),
                        target.hitPosition()
                ));

                BackToTheBase.INSTANCE.getLogger().info("Looking at and clicking pearl button location {} for {}.", location.getNumber(), playerName);

                if (returnConfig.isEnabled()) {
                    MovementSync.INSTANCE.getMovementController().addMovement(new WaitTicksMovement(3));
                    MovementSync.INSTANCE.getMovementController().addMovement(new ReturnAfterUseMovement(playerName, returnConfig.getLocation()));
                } else {
                    MovementSync.INSTANCE.getMovementController().addMovement(new FinishBackActionMovement());
                }
                handedOff = true;
            } finally {
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
                releaseBackAction();
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
            return 50;
        }

        @Override
        public void onStop() {
        }
    }

    private static class ReturnAfterUseMovement extends Movement {
        private final String playerName;
        private final PlayerBaseConfig.ReturnLocation returnLocation;
        private boolean handedOff;

        private ReturnAfterUseMovement(String playerName, PlayerBaseConfig.ReturnLocation returnLocation) {
            this.playerName = playerName;
            this.returnLocation = returnLocation;
        }

        @Override
        public void init() {
            try {
                if (returnLocation == null) {
                    BackToTheBase.INSTANCE.getLogger().warn("return.enabled is true, but return.location is missing.");
                    MovementSync.INSTANCE.getMovementController().addMovement(new FinishBackActionMovement());
                    handedOff = true;
                    return;
                }

                Node goal = new Node(returnLocation.getX(), returnLocation.getY(), returnLocation.getZ());
                Vector3d currentPos = MovementSync.INSTANCE.position.get();
                Node start = new Node((int)Math.floor(currentPos.x), (int)Math.floor(currentPos.y), (int)Math.floor(currentPos.z));
                BackToTheBase.INSTANCE.getLogger().info("Starting return to {} {} {} for {}.", goal.x, goal.y, goal.z, playerName);
                if (!pathTo(start, goal)) {
                    releaseBackAction();
                    return;
                }
                MovementSync.INSTANCE.getMovementController().addMovement(new FinishBackActionMovement());
                handedOff = true;
            } finally {
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
                releaseBackAction();
            }
        }
    }

    private static class FinishBackActionMovement extends Movement {
        @Override
        public void init() {
            releaseBackAction();
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
            releaseBackAction();
        }
    }
}
