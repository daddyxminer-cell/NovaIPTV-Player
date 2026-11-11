package com.nova.iptv.advanced;

import android.app.Activity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.CompoundButton;

/** Binds only the added card; existing Settings controls and Save behavior stay intact. */
public final class AdvancedSettings {
    private static View find(Activity activity, String tag) {
        return activity.getWindow().getDecorView().findViewWithTag(tag);
    }

    public static void bind(Activity activity) {
        AdvancedNetwork.init(activity);
        NetworkPolicy.Config c = AdvancedNetwork.POLICY.current();
        Switch ua = (Switch) find(activity, "advanced_ua_switch");
        Switch referer = (Switch) find(activity, "advanced_referer_switch");
        EditText uaText = (EditText) find(activity, "advanced_ua_text");
        EditText refererText = (EditText) find(activity, "advanced_referer_text");
        uaText.setText(c.userAgent);
        refererText.setText(c.referer);
        ua.setChecked(c.userAgentEnabled);
        referer.setChecked(c.refererEnabled);
        uaText.setEnabled(c.userAgentEnabled);
        refererText.setEnabled(c.refererEnabled);
        ua.setOnCheckedChangeListener((button, checked) -> uaText.setEnabled(checked));
        referer.setOnCheckedChangeListener((button, checked) -> refererText.setEnabled(checked));
        Spinner dns = (Spinner) find(activity, "advanced_dns");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
            android.R.layout.simple_spinner_item, new String[]{
                "System Default", "Cloudflare DNS (1.1.1.1 / 1.0.0.1)",
                "Google DNS (8.8.8.8 / 8.8.4.4)"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        dns.setAdapter(adapter);
        dns.setSelection(c.dnsMode);
        ((CompoundButton) find(activity, "advanced_fragmentation")).setChecked(c.fragmentationEnabled);
        ((CompoundButton) find(activity, "advanced_omit_sni")).setChecked(c.omitSniEnabled);
        ((CompoundButton) find(activity, "advanced_continuous")).setChecked(c.continuousFragmentationEnabled);
        ((CompoundButton) find(activity, "advanced_http_padding")).setChecked(c.httpPaddingEnabled);
        ((CompoundButton) find(activity, "advanced_host_case")).setChecked(c.hostCaseEnabled);
        CompoundButton unavailable = (CompoundButton) find(activity, "advanced_tls_padding_unavailable");
        unavailable.setChecked(false);
        unavailable.setEnabled(false);
    }

    public static boolean save(Activity activity) {
        Switch ua = (Switch) find(activity, "advanced_ua_switch");
        Switch referer = (Switch) find(activity, "advanced_referer_switch");
        EditText uaText = (EditText) find(activity, "advanced_ua_text");
        EditText refererText = (EditText) find(activity, "advanced_referer_text");
        String uaValue = uaText.getText().toString().trim();
        String refValue = refererText.getText().toString().trim();
        uaText.setError(null);
        refererText.setError(null);
        if (ua.isChecked() && !NetworkPolicy.validHeader(uaValue, 1024)) {
            uaText.setError("Enter a User-Agent (printable English characters, max 1024; no line breaks).");
            uaText.requestFocus();
            return false;
        }
        if (referer.isChecked() && !NetworkPolicy.validReferer(refValue)) {
            refererText.setError("Enter an http:// or https:// URL without spaces, credentials or a fragment.");
            refererText.requestFocus();
            return false;
        }
        Spinner dns = (Spinner) find(activity, "advanced_dns");
        AdvancedNetwork.save(new NetworkPolicy.Config(ua.isChecked(), uaValue,
            referer.isChecked(), refValue, dns.getSelectedItemPosition(),
            ((CompoundButton) find(activity, "advanced_fragmentation")).isChecked(),
            ((CompoundButton) find(activity, "advanced_omit_sni")).isChecked(),
            ((CompoundButton) find(activity, "advanced_continuous")).isChecked(),
            ((CompoundButton) find(activity, "advanced_http_padding")).isChecked(),
            ((CompoundButton) find(activity, "advanced_host_case")).isChecked()));
        // Deliberately log no URLs, accounts, tokens, or generated padding values.
        android.util.Log.i("NovaNetwork", "Saved experimental flags: continuous_outbound="
            + ((CompoundButton) find(activity, "advanced_continuous")).isChecked()
            + ", http_header_padding=" + ((CompoundButton) find(activity, "advanced_http_padding")).isChecked()
            + "; new connections only; TLS record padding unavailable");
        return true;
    }

    private AdvancedSettings() { }
}
