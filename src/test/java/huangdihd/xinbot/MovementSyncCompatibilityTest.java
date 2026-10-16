package huangdihd.xinbot;

import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xin.bbtt.Block.BlockState;
import xin.bbtt.MovementSync;
import xin.bbtt.movement.Movement;
import xin.bbtt.movement.MovementController;
import xin.bbtt.movements.PathMovement;
import xin.bbtt.pathfinding.BuiltinMovementType;
import xin.bbtt.pathfinding.Node;
import xin.bbtt.pathfinding.PathStep;
import xin.bbtt.tasks.MovementTask;
import xin.bbtt.world.World;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementSyncCompatibilityTest {
    private MovementSync previousMovementSync;
    private BackToTheBase previousPlugin;
    private final List<Movement> queued = new ArrayList<>();

    @BeforeEach
    void setUp() {
        previousMovementSync = MovementSync.INSTANCE;
        previousPlugin = BackToTheBase.INSTANCE;
        BackToTheBase.INSTANCE = new BackToTheBase();
    }

    @AfterEach
    void tearDown() {
        OnPrivateChat.cancelActiveAction();
        MovementSync.INSTANCE = previousMovementSync;
        BackToTheBase.INSTANCE = previousPlugin;
    }

    @Test
    void queuesButtonPathFromBottomSlabUsingNewNodeHeight() throws Exception {
        useFloor(13197, 64, 64.5); // Bottom stone slab in MovementSync's block registry.

        assertTrue(queueButton());
        assertEquals(2, queued.size());
        List<PathStep> path = queuedPath();
        assertEquals(new Node(0, 65, 0), path.get(0).getNode());
        assertEquals(MovementSync.INSTANCE.getActiveGoal(), path.get(path.size() - 1).getNode().toVector());
        assertEquals(65, path.get(path.size() - 1).getNode().y);
    }

    @Test
    void stillQueuesButtonPathOnFullBlocks() throws Exception {
        useFloor(1, 63, 64.0);

        assertTrue(queueButton());
        assertEquals(2, queued.size());
        List<PathStep> path = queuedPath();
        assertEquals(new Node(0, 64, 0), path.get(0).getNode());
        assertEquals(MovementSync.INSTANCE.getActiveGoal(), path.get(path.size() - 1).getNode().toVector());
    }

    @Test
    void nearbyButtonSkipsWalking() throws Exception {
        useFloor(13197, 64, 64.5);
        MovementSync.INSTANCE.position.set(new Vector3d(7.5, 64.5, 0.5));

        assertTrue(queueButton());
        assertEquals(1, queued.size());
        assertEquals("PrepareButtonClickMovement", queued.get(0).getClass().getSimpleName());
    }

    @Test
    void doesNotChooseStandingPositionsOverAir() throws Exception {
        useFloor(0, 64, 64.5);

        assertFalse(queueButton());
        assertTrue(queued.isEmpty());
    }

    @Test
    void doesNotAcceptPartialPathAcrossAnUnwalkableGap() throws Exception {
        useFloor(1, 63, 64.0, 4);

        assertFalse(queueButton());
        assertTrue(queued.isEmpty());
    }

    @Test
    void candidateSearchDoesNotScaleWithBotHeight() throws Exception {
        int[] lookups = {0};
        World world = new World() {
            @Override
            public boolean chunkLoaded(int x, int z) {
                lookups[0]++;
                return false;
            }
        };
        Method method = OnPrivateChat.class.getDeclaredMethod("findStandingCandidates", World.class,
                org.cloudburstmc.math.vector.Vector3i.class, Vector3d.class, Vector3d.class);
        method.setAccessible(true);
        Object button = org.cloudburstmc.math.vector.Vector3i.from(8, 65, 0);
        Vector3d hit = new Vector3d(8, 65.5, 0.5);
        method.invoke(null, world, button, hit, new Vector3d(0.5, 64, 0.5));
        int nearbyLookups = lookups[0];
        lookups[0] = 0;
        method.invoke(null, world, button, hit, new Vector3d(0.5, 300, 0.5));

        assertTrue(nearbyLookups > 0);
        assertEquals(nearbyLookups, lookups[0]);
        assertTrue(lookups[0] <= 343);
    }

    @Test
    void sameCellDestinationStillQueuesCenteringMovement() throws Exception {
        useFloor(1, 63, 64.0);
        Node goal = new Node(0, 64, 0);
        Object action = acquireAction();
        Method method = OnPrivateChat.class.getDeclaredMethod("queuePath", action.getClass(), Node.class, List.class);
        method.setAccessible(true);

        method.invoke(null, action, goal, List.of(new PathStep(goal, BuiltinMovementType.WALK)));

        assertEquals(1, queued.size());
        assertEquals(goal, queuedPath().get(0).getNode());
    }

    @Test
    void fenceAtBuildLimitRemainsAValidStandingCandidate() throws Exception {
        World world = new World() {
            @Override
            public boolean chunkLoaded(int x, int z) {
                return true;
            }

            @Override
            public int getBlockAt(Vector3d position) {
                return (int) Math.floor(position.x) == 6 && (int) Math.floor(position.y) == 319
                        && (int) Math.floor(position.z) == 0 ? 6795 : 0; // Oak fence.
            }
        };
        Method method = OnPrivateChat.class.getDeclaredMethod("findStandingCandidates", World.class,
                org.cloudburstmc.math.vector.Vector3i.class, Vector3d.class, Vector3d.class);
        method.setAccessible(true);
        List<?> candidates = (List<?>) method.invoke(null, world,
                org.cloudburstmc.math.vector.Vector3i.from(8, 319, 0),
                new Vector3d(8, 319.5, 0.5), new Vector3d(0.5, 320.5, 0.5));
        List<Node> nodes = new ArrayList<>();
        for (Object candidate : candidates) {
            Method node = candidate.getClass().getDeclaredMethod("node");
            node.setAccessible(true);
            nodes.add((Node) node.invoke(candidate));
        }

        assertTrue(nodes.contains(new Node(6, 321, 0)));
    }

    @Test
    void returnPathAlsoStartsAtSlabNodeHeight() throws Exception {
        useFloor(13197, 64, 64.5);
        Object action = acquireAction();
        Class<?> type = Class.forName(OnPrivateChat.class.getName() + "$ReturnAfterUseMovement");
        Constructor<?> constructor = type.getDeclaredConstructor(action.getClass(), String.class, PlayerBaseConfig.ReturnLocation.class);
        constructor.setAccessible(true);
        Movement movement = (Movement) constructor.newInstance(action, "Steve", new PlayerBaseConfig.ReturnLocation(2, 65, 0));

        movement.init();

        assertEquals(2, queued.size());
        List<PathStep> path = queuedPath();
        assertEquals(new Node(0, 65, 0), path.get(0).getNode());
        assertEquals(new Node(2, 65, 0), path.get(path.size() - 1).getNode());
    }

    @Test
    void returnKeepsPartialRouteForMovementSyncReplanning() throws Exception {
        useFloor(1, 63, 64.0);
        Object action = acquireAction();
        Class<?> type = Class.forName(OnPrivateChat.class.getName() + "$ReturnAfterUseMovement");
        Constructor<?> constructor = type.getDeclaredConstructor(action.getClass(), String.class, PlayerBaseConfig.ReturnLocation.class);
        constructor.setAccessible(true);
        Movement movement = (Movement) constructor.newInstance(action, "Steve", new PlayerBaseConfig.ReturnLocation(20, 64, 0));

        movement.init();

        assertEquals(2, queued.size());
        List<PathStep> path = queuedPath();
        assertEquals(new Node(8, 64, 0), path.get(path.size() - 1).getNode());
        assertEquals(new org.joml.Vector3i(20, 64, 0), MovementSync.INSTANCE.getActiveGoal());
    }

    @Test
    void schedulerWaitsThreeTicksBeforeFinishing() throws Exception {
        Class<?> type = Class.forName(OnPrivateChat.class.getName() + "$WaitTicksMovement");
        Constructor<?> constructor = type.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        Movement movement = (Movement) constructor.newInstance(3);
        List<Movement> finished = new ArrayList<>();
        MovementController controller = new MovementController() {
            @Override
            public boolean finishCurrentMovement(Movement expected, long activationId) {
                finished.add(expected);
                return true;
            }
        };
        MovementTask task = new MovementTask(movement, controller, 1);

        task.run();
        task.run();
        assertTrue(finished.isEmpty());
        assertFalse(movement.isFinished());
        task.run();
        assertEquals(List.of(movement), finished);
        assertTrue(movement.isFinished());
    }

    private void useFloor(int stateId, int floorY, double feetY) {
        useFloor(stateId, floorY, feetY, -1);
    }

    private void useFloor(int stateId, int floorY, double feetY, int gapX) {
        World fixtureWorld = new World() {
            @Override
            public boolean chunkLoaded(int x, int z) {
                return true;
            }

            @Override
            public int getBlockAt(Vector3d position) {
                int x = (int) Math.floor(position.x);
                int y = (int) Math.floor(position.y);
                int z = (int) Math.floor(position.z);
                return x >= 0 && x <= 8 && x != gapX && y == floorY && z == 0 ? stateId : 0;
            }

            @Override
            public BlockState getBlockStateAt(Vector3d position) {
                if ((int) Math.floor(position.x) == 8 && (int) Math.floor(position.y) == 65
                        && (int) Math.floor(position.z) == 0) {
                    return new BlockState("minecraft:stone_button", 0,
                            Map.of("face", "wall", "facing", "west"), "empty", 0.5, true, "rock");
                }
                return super.getBlockStateAt(position);
            }
        };
        MovementController controller = new MovementController() {
            @Override
            public void addMovement(Movement movement) {
                queued.add(movement);
            }
        };
        MovementSync sync = new MovementSync() {
            @Override
            public World getWorld() {
                return fixtureWorld;
            }

            @Override
            public MovementController getMovementController() {
                return controller;
            }
        };
        sync.position.set(new Vector3d(0.5, feetY, 0.5));
    }

    private boolean queueButton() throws Exception {
        Object action = acquireAction();
        Method method = OnPrivateChat.class.getDeclaredMethod("queueButtonAction", action.getClass(),
                String.class, ButtonLocation.class, PlayerBaseConfig.ReturnConfig.class);
        method.setAccessible(true);
        return (boolean) method.invoke(new OnPrivateChat(), action, "Steve",
                new ButtonLocation("1", 8, 65, 0), new PlayerBaseConfig.ReturnConfig());
    }

    private Object acquireAction() throws Exception {
        Method method = OnPrivateChat.class.getDeclaredMethod("acquireBackAction", MovementSync.class);
        method.setAccessible(true);
        Object action = method.invoke(null, MovementSync.INSTANCE);
        assertNotNull(action);
        return action;
    }

    @SuppressWarnings("unchecked")
    private List<PathStep> queuedPath() throws Exception {
        PathMovement movement = assertInstanceOf(PathMovement.class, queued.get(0));
        Field field = PathMovement.class.getDeclaredField("path");
        field.setAccessible(true);
        return (List<PathStep>) field.get(movement);
    }
}
