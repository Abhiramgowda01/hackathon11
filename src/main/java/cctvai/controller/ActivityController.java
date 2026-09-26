package cctvai.controller;

import cctvai.service.LiveActivityService;
import cctvai.service.ObjectActivityTracker;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@CrossOrigin(origins = "*")
public class ActivityController {
    private final LiveActivityService liveActivityService;

    public ActivityController(
            LiveActivityService liveActivityService
    ) {
        this.liveActivityService =
                liveActivityService;
    }

    @GetMapping("/activity/live")
    public List<ObjectActivityTracker.ActivityState>
    getLiveActivity() {
        return liveActivityService
                .getCurrentActivities();
    }
}