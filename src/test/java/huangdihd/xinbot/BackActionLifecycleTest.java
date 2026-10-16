package huangdihd.xinbot;

import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xin.bbtt.Block.BlockState;
import xin.bbtt.MovementSync;
import xin.bbtt.movement.Movement;
import xin.bbtt.movement.MovementController;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackActionLifecycleTest {
    private MovementSync previousSync;
    private BackToTheBase previousPlugin;
    private Object previousAction;
    private Class<?> actionType;
    private AtomicReference<Object> activeAction;
    private final List<Movement> queued = new ArrayList<>();
    private Movement current;
    private boolean paused;
    private boolean brokenWorld;
    private boolean failAfterClickQueued;
    private volatile Runnable beforeNavigationAction;
    private MovementSync sync;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        previousSync = MovementSync.INSTANCE;
        previousPlugin = BackToTheBase.INSTANCE;
        BackToTheBase.INSTANCE = new BackToTheBase();
        actionType = Class.forName(OnPrivateChat.class.getName() + "$BackAction");
        Field field = OnPrivateChat.class.getDeclaredField("BACK_ACTION");
        field.setAccessible(true);
        activeAction = (AtomicReference<Object>) field.get(null);
        previousAction = activeAction.getAndSet(null);
        MovementController controller = new MovementController() {
            @Override
            public void addMovement(Movement movement) {
                if (failAfterClickQueued && queued.stream().anyMatch(UseItemOnMovement.class::isInstance)) {
                    throw new IllegalStateException("Cannot enqueue continuation");
                }
                queued.add(movement);
            }

            @Override
            public Movement getCurrentMovement() {
                return current;
            }

            @Override
            public List<Movement> getMovements() {
                return new ArrayList<>(queued);
            }

            @Override
            public boolean isPaused() {
                return paused;
            }
        };
        xin.bbtt.world.World fixtureWorld = new xin.bbtt.world.World() {
            @Override
            public BlockState getBlockStateAt(Vector3d position) {
                if (brokenWorld) throw new IllegalStateException("World unavailable");
                return new BlockState("minecraft:stone_button", 0,
                        Map.of("face", "wall", "facing", "west"), "empty", 0.5, true, "rock");
            }

            @Override
            public boolean canSee(Vector3d start, Vector3d end) {
                return true;
            }
        };
        sync = new MovementSync() {
            @Override
            public MovementController getMovementController() {
                return controller;
            }

            @Override
            public xin.bbtt.world.World getWorld() {
                return fixtureWorld;
            }

            @Override
            public boolean runIfNavigationRequestCurrent(long generation, Runnable action) {
                Runnable hook = beforeNavigationAction;
                if (hook != null) hook.run();
                return super.runIfNavigationRequestCurrent(generation, action);
            }
        };
        sync.position.set(new Vector3d(0.5, 64, 0.5));
    }

    @AfterEach
    void tearDown() {
        OnPrivateChat.cancelActiveAction();
        activeAction.set(previousAction);
        MovementSync.INSTANCE = previousSync;
        BackToTheBase.INSTANCE = previousPlugin;
    }

    @Test
    void refusesFollowingPausedAndOtherQueuedMovement() throws Exception {
        sync.setFollowTargetId(42);
        assertNull(acquire());
        assertEquals(42, sync.getFollowTargetId());
        sync.setFollowTargetId(-1);
        paused = true;
        assertNull(acquire());
        paused = false;
        current = idleMovement();
        assertNull(acquire());
        queued.add(current);
        current = null;
        assertNull(acquire());
        assertEquals(1, queued.size());
    }

    @Test
    void doesNotReacquireWhileFirstActionIsStillPlanning() throws Exception {
        Object first = acquire();
        assertNotNull(first);
        assertNull(acquire());
        assertSame(first, activeAction.get());
    }

    @Test
    void acceptsIdleControllerWithStaleGoalAndReservesNewGeneration() throws Exception {
        sync.setActiveGoal(new org.joml.Vector3i(90, 64, 0));
        long previousGeneration = sync.getNavigationGeneration();
        Object action = acquire();

        assertTrue(queueButton(action));
        assertTrue(sync.getNavigationGeneration() > previousGeneration);
        assertNull(sync.getActiveGoal());
        assertEquals(1, queued.size());
    }

    @Test
    void canceledEmptyQueueCanBeUsedImmediately() throws Exception {
        Object first = acquire();
        assertTrue(queueButton(first));
        queued.clear(); // MovementSync.cancelAll clears pending movements without calling onStop.

        Object second = acquire();

        assertNotNull(second);
        assertNotSame(first, second);
    }

    @Test
    void replacingNavigationDoesNotCancelTheReplacement() throws Exception {
        Object first = acquire();
        assertTrue(queueButton(first));
        sync.beginNavigationRequest();
        queued.clear();
        Movement replacement = idleMovement();
        current = replacement;
        sync.setFollowTargetId(42);
        long generation = sync.getNavigationGeneration();

        assertNull(acquire());

        assertNull(activeAction.get());
        assertSame(replacement, current);
        assertEquals(42, sync.getFollowTargetId());
        assertEquals(generation, sync.getNavigationGeneration());
    }

    @Test
    void oldCompletionCannotUnlockANewerAction() throws Exception {
        Object first = acquire();
        Movement oldFinish = movement("FinishBackActionMovement", new Class<?>[]{actionType}, first);
        OnPrivateChat.cancelActiveAction();
        Object second = acquire();

        oldFinish.init();
        oldFinish.onStop();

        assertSame(second, activeAction.get());
    }

    @Test
    void prepareFailureReleasesLockEvenWithoutSchedulerStopCallback() throws Exception {
        Object action = acquire();
        Movement prepare = prepare(action);
        brokenWorld = true;

        assertDoesNotThrow(prepare::init);

        assertTrue(prepare.isFinished());
        assertNull(activeAction.get());
        assertNotNull(acquire());
    }

    @Test
    void returnFailureReleasesLockEvenWithoutSchedulerStopCallback() throws Exception {
        Object action = acquire();
        Movement returning = movement("ReturnAfterUseMovement",
                new Class<?>[]{actionType, String.class, PlayerBaseConfig.ReturnLocation.class},
                action, "Steve", new PlayerBaseConfig.ReturnLocation(5, 64, 0));
        sync.position.set(null);

        assertDoesNotThrow(returning::init);

        assertTrue(returning.isFinished());
        assertNull(activeAction.get());
    }

    @Test
    void canceledActionCannotSendAnAlreadyQueuedClick() throws Exception {
        Object action = acquire();
        prepare(action).init();
        UseItemOnMovement click = assertInstanceOf(UseItemOnMovement.class, queued.get(0));
        OnPrivateChat.cancelActiveAction();

        assertDoesNotThrow(click::onTick); // No Bot session is present: a packet send would fail.
        assertTrue(click.isFinished());
    }

    @Test
    void failedHandoffCannotSendAnAlreadyQueuedClick() throws Exception {
        Object action = acquire();
        failAfterClickQueued = true;

        assertDoesNotThrow(() -> prepare(action).init());

        UseItemOnMovement click = assertInstanceOf(UseItemOnMovement.class, queued.get(0));
        assertNull(activeAction.get());
        assertDoesNotThrow(click::onTick);
        assertTrue(click.isFinished());
    }

    @Test
    void timedOutActionDisablesItsClickWithoutRemovingOtherQueuedWork() throws Exception {
        Constructor<?> constructor = actionType.getDeclaredConstructor(MovementSync.class, long.class);
        constructor.setAccessible(true);
        Object expired = constructor.newInstance(sync, System.currentTimeMillis() - 60_000);
        activeAction.set(expired);
        prepare(expired).init();
        Movement unrelated = idleMovement();
        queued.add(unrelated);
        UseItemOnMovement click = assertInstanceOf(UseItemOnMovement.class, queued.get(0));

        assertNull(acquire());

        assertNull(activeAction.get());
        assertTrue(queued.contains(unrelated));
        assertDoesNotThrow(click::onTick);
        assertTrue(click.isFinished());
    }

    @Test
    void disableStopsItsOwnClickAndPreservesUnrelatedWork() throws Exception {
        Object action = acquire();
        prepare(action).init();
        UseItemOnMovement click = assertInstanceOf(UseItemOnMovement.class, queued.get(0));
        Movement unrelated = idleMovement();
        queued.add(unrelated);
        sync.beginNavigationRequest();
        sync.setFollowTargetId(42);
        long generation = sync.getNavigationGeneration();

        BackToTheBase.INSTANCE.onDisable();
        BackToTheBase.INSTANCE.onUnload();

        assertNull(activeAction.get());
        assertTrue(queued.contains(unrelated));
        assertEquals(generation, sync.getNavigationGeneration());
        assertEquals(42, sync.getFollowTargetId());
        assertDoesNotThrow(click::onTick);
        assertTrue(click.isFinished());
    }

    @Test
    void cancellationWaitsForNavigationLockBeforeInvalidatingTheToken() throws Exception {
        Object action = acquire();
        CountDownLatch attempted = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?>[] cancellation = new Future<?>[1];
        try {
            sync.runIfNavigationRequestCurrent(sync.getNavigationGeneration(), () -> {
                beforeNavigationAction = attempted::countDown;
                cancellation[0] = executor.submit(OnPrivateChat::cancelActiveAction);
                try {
                    assertTrue(attempted.await(2, TimeUnit.SECONDS));
                    assertSame(action, activeAction.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                } finally {
                    beforeNavigationAction = null;
                }
            });
            cancellation[0].get(2, TimeUnit.SECONDS);
            assertNull(activeAction.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void supersededNavigationPreventsClickAtThePacketBoundary() throws Exception {
        Object action = acquire();
        prepare(action).init();
        UseItemOnMovement click = assertInstanceOf(UseItemOnMovement.class, queued.get(0));
        beforeNavigationAction = () -> {
            beforeNavigationAction = null;
            sync.beginNavigationRequest();
        };

        assertDoesNotThrow(click::onTick);

        assertTrue(click.isFinished());
    }

    @Test
    void failedInitCompletesNormallyInTheRealMovementController() throws Exception {
        sync = new MovementSync() {
            @Override
            public xin.bbtt.world.World getWorld() {
                throw new IllegalStateException("World unavailable");
            }
        };
        Object action = acquire();
        Movement prepare = prepare(action);
        try {
            sync.getMovementController().addMovement(prepare);
            // The zero-delay movement tick precedes this barrier on the single scheduler thread.
            sync.movementService.submit(() -> { }).get(2, TimeUnit.SECONDS);

            assertTrue(prepare.isFinished());
            assertNull(sync.getMovementController().getCurrentMovement());
            assertNull(activeAction.get());
            assertNotNull(acquire());
        } finally {
            if (sync.movementService != null) sync.movementService.shutdownNow();
        }
    }

    private Object acquire() throws Exception {
        Method method = OnPrivateChat.class.getDeclaredMethod("acquireBackAction", MovementSync.class);
        method.setAccessible(true);
        return method.invoke(null, sync);
    }

    private boolean queueButton(Object action) throws Exception {
        Method method = OnPrivateChat.class.getDeclaredMethod("queueButtonAction", actionType,
                String.class, ButtonLocation.class, PlayerBaseConfig.ReturnConfig.class);
        method.setAccessible(true);
        return (boolean) method.invoke(new OnPrivateChat(), action, "Steve",
                new ButtonLocation("1", 2, 65, 0), new PlayerBaseConfig.ReturnConfig());
    }

    private Movement prepare(Object action) throws Exception {
        return movement("PrepareButtonClickMovement",
                new Class<?>[]{actionType, String.class, ButtonLocation.class, PlayerBaseConfig.ReturnConfig.class},
                action, "Steve", new ButtonLocation("1", 2, 65, 0), new PlayerBaseConfig.ReturnConfig());
    }

    private Movement movement(String name, Class<?>[] parameters, Object... values) throws Exception {
        Constructor<?> constructor = Class.forName(OnPrivateChat.class.getName() + "$" + name)
                .getDeclaredConstructor(parameters);
        constructor.setAccessible(true);
        return (Movement) constructor.newInstance(values);
    }

    private Movement idleMovement() {
        return new Movement() {
            @Override public void init() { }
            @Override public void onTick() { }
            @Override public long getTime() { return -1; }
            @Override public void onStop() { }
        };
    }
}
