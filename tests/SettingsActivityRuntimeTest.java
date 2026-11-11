import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Resources;
import android.util.TypedValue;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Spinner;
import androidx.appcompat.widget.SwitchCompat;
import com.nova.iptv.ui.PlayerActivity;
import com.nova.iptv.ui.SettingsActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/** Loads classes and binary resources from the actual rebuilt APK, then measures the full screen. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={22, 23, 28, 33}, manifest=Config.NONE)
public class SettingsActivityRuntimeTest {
    private <T extends Activity> ActivityController<T> attached(Class<T> type, Intent intent) {
        ActivityController<T> controller=Robolectric.buildActivity(type,intent);
        T activity=controller.get();
        ReflectionHelpers.callInstanceMethod(activity.getAssets(),"addAssetPath",
            ClassParameter.from(String.class,System.getProperty("nova.settings.apk")));
        int theme=activity.getResources().getIdentifier("Theme.NovaIptv","style","com.nova.iptv");
        assertTrue("APK theme must resolve",theme!=0);
        activity.setTheme(theme);
        return controller;
    }
    private int id(Activity activity,String name) {
        int id=activity.getResources().getIdentifier(name,"id","com.nova.iptv");
        assertTrue(name+" must resolve",id!=0);
        return id;
    }
    private void verifyMeasuredScreen(Activity activity) {
        View decor=activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),
                      View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY));
        decor.layout(0,0,1280,720);
        for (String tag:new String[]{"advanced_fragmentation","advanced_omit_sni","advanced_continuous",
                                     "advanced_http_padding","advanced_host_case","advanced_tls_padding_unavailable"}) {
            SwitchCompat toggle=(SwitchCompat)decor.findViewWithTag(tag);
            assertNotNull(tag,toggle);
            assertFalse("Internal text rendering must be disabled for "+tag,toggle.getShowText());
            assertNotNull("On label must be safe for accessibility/measurement",toggle.getTextOn());
            assertNotNull("Off label must be safe for accessibility/measurement",toggle.getTextOff());
            for (String attr:new String[]{"colorOutline","colorOnSurfaceVariant","colorSurfaceVariant","colorPrimaryContainer"}) {
                Resources r=toggle.getResources(); TypedValue value=new TypedValue();
                assertTrue(attr+" must resolve in the switch context",
                    toggle.getContext().getTheme().resolveAttribute(r.getIdentifier(attr,"attr","com.nova.iptv"),value,true));
            }
            toggle.measure(View.MeasureSpec.makeMeasureSpec(1100,View.MeasureSpec.AT_MOST),
                           View.MeasureSpec.makeMeasureSpec(64,View.MeasureSpec.EXACTLY));
        }
        assertFalse(((CompoundButton)decor.findViewWithTag("advanced_tls_padding_unavailable")).isEnabled());
        assertTrue("No failed tint inflation is allowed",ShadowLog.getLogs().stream().noneMatch(
            log -> "ResourcesCompat".equals(log.tag) && log.msg.contains("Failed to inflate ColorStateList")));
    }
    @Test public void opensFromTheOriginalPlayerSettingsButton() {
        ActivityController<PlayerActivity> player=attached(PlayerActivity.class,new Intent());
        player.create();
        assertTrue(player.get().findViewById(id(player.get(),"pBufFull")).performClick());
        Intent destination=((ShadowActivity)Shadow.extract(player.get())).getNextStartedActivity();
        assertNotNull("Player button must start an activity",destination);
        assertEquals(SettingsActivity.class.getName(),destination.getComponent().getClassName());
        ActivityController<SettingsActivity> settings=attached(SettingsActivity.class,destination);
        settings.create().start().resume().visible();
        assertFalse(settings.get().isFinishing());
        verifyMeasuredScreen(settings.get());
        settings.get().findViewById(id(settings.get(),"btnBack")).performClick();
        assertTrue("Back button must leave Settings normally",settings.get().isFinishing());
        settings.pause().stop().destroy(); player.destroy();
    }
    @Test public void savesAdvancedSettingsAndReopensWithoutCrashing() {
        ActivityController<SettingsActivity> first=attached(SettingsActivity.class,new Intent());
        first.create().start().resume().visible();
        verifyMeasuredScreen(first.get());
        View root=first.get().getWindow().getDecorView();
        ((CompoundButton)root.findViewWithTag("advanced_ua_switch")).setChecked(true);
        ((EditText)root.findViewWithTag("advanced_ua_text")).setText("SettingsRegression/9.0.1");
        ((Spinner)root.findViewWithTag("advanced_dns")).setSelection(2);
        ((CompoundButton)root.findViewWithTag("advanced_continuous")).setChecked(true);
        ((CompoundButton)root.findViewWithTag("advanced_host_case")).setChecked(true);
        first.get().findViewById(id(first.get(),"btnSave")).performClick();
        assertTrue("Save must finish Settings, not the app",first.get().isFinishing());
        first.pause().stop().destroy();
        ActivityController<SettingsActivity> reopened=attached(SettingsActivity.class,new Intent());
        reopened.create().start().resume().visible();
        verifyMeasuredScreen(reopened.get());
        root=reopened.get().getWindow().getDecorView();
        assertEquals("SettingsRegression/9.0.1",((EditText)root.findViewWithTag("advanced_ua_text")).getText().toString());
        assertTrue(((CompoundButton)root.findViewWithTag("advanced_ua_switch")).isChecked());
        assertTrue(((CompoundButton)root.findViewWithTag("advanced_continuous")).isChecked());
        assertTrue(((CompoundButton)root.findViewWithTag("advanced_host_case")).isChecked());
        assertEquals(2,((Spinner)root.findViewWithTag("advanced_dns")).getSelectedItemPosition());
        reopened.pause().stop().destroy();
    }
}
