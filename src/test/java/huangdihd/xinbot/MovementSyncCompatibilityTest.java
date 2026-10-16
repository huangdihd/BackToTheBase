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
    void returnPathAlsoStartsAtSlabNodeHeight() throws Exception {
        useFloor(13197, 64, 64.5);
        Class<?> type = Class.forName(OnPrivateChat.class.getName() + "$ReturnAfterUseMovement");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, PlayerBaseConfig.ReturnLocation.class);
        constructor.setAccessible(true);
        Movement movement = (Movement) constructor.newInstance("Steve", new PlayerBaseConfig.ReturnLocation(2, 65, 0));

        movement.init();

        assertEquals(2, queued.size());
        List<PathStep> path = queuedPath();
        assertEquals(new Node(0, 65, 0), path.get(0).getNode());
        assertEquals(new Node(2, 65, 0), path.get(path.size() - 1).getNode());
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
                return x >= 0 && x <= 8 && y == floorY && z == 0 ? stateId : 0;
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
        Method method = OnPrivateChat.class.getDeclaredMethod("queueButtonAction", MovementSync.class,
                String.class, ButtonLocation.class, PlayerBaseConfig.ReturnConfig.class);
        method.setAccessible(true);
        return (boolean) method.invoke(new OnPrivateChat(), MovementSync.INSTANCE, "Steve",
                new ButtonLocation("1", 8, 65, 0), new PlayerBaseConfig.ReturnConfig());
    }

    @SuppressWarnings("unchecked")
    private List<PathStep> queuedPath() throws Exception {
        PathMovement movement = assertInstanceOf(PathMovement.class, queued.get(0));
        Field field = PathMovement.class.getDeclaredField("path");
        field.setAccessible(true);
        return (List<PathStep>) field.get(movement);
    }
}
