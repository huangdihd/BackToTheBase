package huangdihd.xinbot;

import org.cloudburstmc.math.vector.Vector3i;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xin.bbtt.MovementSync;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.mcbot.event.EventHandler;
import xin.bbtt.mcbot.event.Listener;
import xin.bbtt.mcbot.events.PrivateChatEvent;
import xin.bbtt.movements.LookAtMovement;

public class OnPrivateChat implements Listener {
    private static final Logger log = LoggerFactory.getLogger(OnPrivateChat.class.getSimpleName());

    @EventHandler
    public void onBack(PrivateChatEvent event) {
        if (!BackToTheBase.getInstance().buttons.containsKey(event.getSender().getName())) return;
        if (!event.getMessage().equals("back ")) return;
        Button button = BackToTheBase.getInstance().buttons.get(event.getSender().getName());
        Vector3d positionDouble = new Vector3d(button.x, button.y, button.z).add(new Vector3d(.5, .5, .5));
        Vector3i positionInt = Vector3i.from(button.x, button.y, button.z);
        if (!Bot.Instance.getPluginManager().isPluginLoaded("MovementSync")) return;
        if (!(Bot.Instance.getPluginManager().getPlugin("MovementSync") instanceof MovementSync movementSync)) return;
        movementSync.getMovementController().addMovement(new LookAtMovement(positionDouble));
        movementSync.movementController.addMovement(new UseItemOnMovement(positionInt, button.direction));
        Bot.Instance.sendChatMessage("已经触发" + event.getSender().getName() + "的滞留珍珠 by BackToTheBase");
    }
}
