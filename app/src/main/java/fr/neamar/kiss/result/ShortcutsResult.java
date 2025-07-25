package fr.neamar.kiss.result;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.UserManager;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.MultiAutoCompleteTextView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.net.URISyntaxException;
import java.util.concurrent.atomic.AtomicReference;

import fr.neamar.kiss.DataHandler;
import fr.neamar.kiss.IconsHandler;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.R;
import fr.neamar.kiss.adapter.RecordAdapter;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.ui.ListPopup;
import fr.neamar.kiss.utils.DrawableUtils;
import fr.neamar.kiss.utils.PackageManagerUtils;
import fr.neamar.kiss.utils.ShortcutUtil;
import fr.neamar.kiss.utils.SpaceTokenizer;
import fr.neamar.kiss.utils.UserHandle;
import fr.neamar.kiss.utils.Utilities;
import fr.neamar.kiss.utils.fuzzy.FuzzyScore;

public class ShortcutsResult extends Result<ShortcutPojo> {

    private static final String TAG = ShortcutsResult.class.getSimpleName();

    private volatile Drawable icon = null;
    private volatile Drawable appDrawable = null;

    private Utilities.AsyncRun mLoadIconTask = null;

    ShortcutsResult(@NonNull ShortcutPojo pojo) {
        super(pojo);
    }

    @NonNull
    @Override
    public View display(final Context context, View view, @NonNull ViewGroup parent, FuzzyScore fuzzyScore) {
        if (view == null)
            view = inflateFromId(context, R.layout.item_shortcut, parent);

        TextView shortcutName = view.findViewById(R.id.item_app_name);

        displayHighlighted(pojo.normalizedName, pojo.getName(), fuzzyScore, shortcutName, context);

        TextView tagsView = view.findViewById(R.id.item_app_tag);

        // Hide tags view if tags are empty
        if (pojo.getTags().isEmpty()) {
            tagsView.setVisibility(View.GONE);
        } else if (displayHighlighted(pojo.getNormalizedTags(), pojo.getTags(),
                fuzzyScore, tagsView, context) || isTagsVisible(context)) {
            tagsView.setVisibility(View.VISIBLE);
        } else {
            tagsView.setVisibility(View.GONE);
        }

        final ImageView shortcutIcon = view.findViewById(R.id.item_shortcut_icon);
        final ImageView appIcon = view.findViewById(R.id.item_app_icon);

        if (!isHideIcons(context)) {
            // set shortcut icon
            this.setAsyncDrawable(shortcutIcon);

            // set app icon
            if (mLoadIconTask != null) {
                mLoadIconTask.cancel();
                mLoadIconTask = null;
            }

            // Prepare
            if (isSubIconVisible(context)) {
                appIcon.setVisibility(View.VISIBLE);
                if (appDrawable != null) {
                    appIcon.setImageDrawable(getAppDrawable(context));
                } else {
                    appIcon.setImageResource(android.R.color.transparent);
                    AtomicReference<Drawable> appDrawable = new AtomicReference<>(null);
                    mLoadIconTask = Utilities.runAsync((task) -> {
                        if (task == mLoadIconTask) {
                            // Retrieve icon for this shortcut
                            appDrawable.set(getAppDrawable(context));
                        }
                    }, (task) -> {
                        if (!task.isCancelled() && task == mLoadIconTask) {
                            appIcon.setImageDrawable(appDrawable.get());
                        }
                    });
                }
            } else {
                appIcon.setVisibility(View.GONE);
            }
        } else {
            appIcon.setImageDrawable(null);
            shortcutIcon.setImageDrawable(null);
        }

        return view;
    }

    private Drawable getAppDrawable(Context context) {
        if (appDrawable == null) {
            synchronized (this) {
                if (appDrawable == null) {
                    IconsHandler iconsHandler = KissApplication.getApplication(context).getIconsHandler();

                    if (pojo.isOreoShortcut()) {
                        // Retrieve activity icon from oreo shortcut
                        appDrawable = getDrawableFromOreoShortcut(context);
                    }

                    if (appDrawable == null) {
                        // Retrieve activity icon by intent URI
                        try {
                            Intent intent = Intent.parseUri(pojo.intentUri, 0);
                            ComponentName componentName = PackageManagerUtils.getComponentName(context, intent);
                            if (componentName != null) {
                                UserHandle userHandle = new UserHandle();
                                appDrawable = iconsHandler.getDrawableIconForPackage(PackageManagerUtils.getLaunchingComponent(context, componentName, userHandle), userHandle);
                            }
                        } catch (NullPointerException e) {
                            Log.e(TAG, "Unable to get activity icon for '" + pojo.getName() + "'", e);
                        } catch (URISyntaxException e) {
                            Log.e(TAG, "Unable to parse uri for '" + pojo.getName() + "'", e);
                        }
                    }

                    if (appDrawable == null) {
                        // Retrieve app icon (no Oreo shortcut or a shortcut from an activity that was removed from an installed app)
                        appDrawable = PackageManagerUtils.getApplicationIcon(context, pojo.packageName);
                        if (appDrawable != null) {
                            appDrawable = iconsHandler.applyIconMask(context, appDrawable);
                        }
                    }
                }
            }
        }
        DrawableUtils.setDisabled(appDrawable, this.pojo.isDisabled());
        return appDrawable;
    }

    @Override
    boolean isDrawableCached() {
        return icon != null;
    }

    @Override
    void setDrawableCache(Drawable drawable) {
        icon = drawable;
    }

    public Drawable getDrawable(Context context) {
        if (!isDrawableCached()) {
            synchronized (this) {
                if (!isDrawableCached()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ShortcutInfo shortcutInfo = getShortCut(context);
                        if (shortcutInfo != null) {
                            final LauncherApps launcherApps = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
                            assert launcherApps != null;
                            try {
                                icon = launcherApps.getShortcutIconDrawable(shortcutInfo, 0);
                            } catch (IllegalStateException e) {
                                // do nothing if user is locked or not running
                                Log.w(TAG, "Unable to get shortcut icon for '" + pojo.getName() + "', user is locked or not running", e);
                            } catch (NullPointerException e) {
                                // shortcuts may use invalid icons, see https://github.com/Neamar/KISS/issues/2158
                                Log.e(TAG, "Unable to get shortcut icon for '" + pojo.getName() + "'", e);
                            }
                        }
                    }
                    if (icon == null) {
                        icon = context.getResources().getDrawable(android.R.drawable.ic_menu_send);
                    }
                    if (icon != null) {
                        icon = DrawableUtils.getThemedDrawable(context, icon);
                        icon = KissApplication.getApplication(context).getIconsHandler().applyIconMask(context, icon);
                    }
                }
            }
        }
        DrawableUtils.setDisabled(icon, this.pojo.isDisabled());
        return icon;
    }

    @Override
    protected void doLaunch(Context context, View v) {
        if (pojo.isOreoShortcut()) {
            // Oreo shortcuts
            doOreoLaunch(context, v);
        } else {
            // Pre-oreo shortcuts
            try {
                Intent intent = Intent.parseUri(pojo.intentUri, 0);
                setSourceBounds(intent, v);
                context.startActivity(intent);
            } catch (Exception e) {
                // Application was just removed?
                Toast.makeText(context, R.string.application_not_found, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void doOreoLaunch(Context context, View v) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            final LauncherApps launcherApps = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
            assert launcherApps != null;

            // Only the default launcher is allowed to start shortcuts
            if (!launcherApps.hasShortcutHostPermission()) {
                Toast.makeText(context, context.getString(R.string.shortcuts_no_host_permission), Toast.LENGTH_LONG).show();
                return;
            }

            ShortcutInfo shortcutInfo = getShortCut(context);
            if (shortcutInfo != null) {
                try {
                    launcherApps.startShortcut(shortcutInfo, v.getClipBounds(), null);
                    return;
                } catch (ActivityNotFoundException | IllegalStateException e) {
                    Log.w(TAG, "Unable to launch shortcut " + pojo.getName(), e);
                }
            }
        }

        // Application removed? Invalid shortcut? Shortcut to an app on an unmounted SD card?
        Toast.makeText(context, R.string.application_not_found, Toast.LENGTH_LONG).show();
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private ShortcutInfo getShortCut(Context context) {
        return ShortcutUtil.getShortCut(context, pojo.packageName, pojo.getOreoId());
    }

    private Drawable getDrawableFromOreoShortcut(Context context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            ShortcutInfo shortcutInfo = getShortCut(context);
            if (shortcutInfo != null && shortcutInfo.getActivity() != null) {
                UserManager manager = (UserManager) context.getSystemService(Context.USER_SERVICE);
                fr.neamar.kiss.utils.UserHandle user = new fr.neamar.kiss.utils.UserHandle(manager.getSerialNumberForUser(shortcutInfo.getUserHandle()), shortcutInfo.getUserHandle());
                IconsHandler iconsHandler = KissApplication.getApplication(context).getIconsHandler();
                return iconsHandler.getDrawableIconForPackage(shortcutInfo.getActivity(), user);
            }
        }
        return null;
    }

    @Override
    ListPopup buildPopupMenu(Context context, ArrayAdapter<ListPopup.Item> adapter, RecordAdapter parent, View parentView) {
        if (!this.pojo.isDynamic() || this.pojo.isPinned()) {
            adapter.add(new ListPopup.Item(context, R.string.menu_favorites_add));
        }
        adapter.add(new ListPopup.Item(context, R.string.menu_favorites_remove));
        adapter.add(new ListPopup.Item(context, R.string.menu_tags_edit));
        adapter.add(new ListPopup.Item(context, R.string.menu_remove));
        if (!this.pojo.isPinned() && this.pojo.isOreoShortcut()) {
            adapter.add(new ListPopup.Item(context, R.string.menu_shortcut_pin));
        }
        if (this.pojo.isPinned()) {
            adapter.add(new ListPopup.Item(context, R.string.menu_shortcut_remove));
        }

        return inflatePopupMenu(adapter, context);
    }

    @Override
    boolean popupMenuClickHandler(Context context, RecordAdapter parent, int stringId, View parentView) {
        if (stringId == R.string.menu_shortcut_pin) {
            pinShortcut(context, pojo);
            return true;
        } else if (stringId == R.string.menu_shortcut_remove) {
            launchUninstall(context, pojo);
            // Also remove item, since it will be uninstalled
            parent.removeResult(context, this);
            return true;
        } else if (stringId == R.string.menu_tags_edit) {
            launchEditTagsDialog(context, pojo);
            return true;
        }
        return super.popupMenuClickHandler(context, parent, stringId, parentView);
    }

    private void launchEditTagsDialog(final Context context, final ShortcutPojo pojo) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(context.getResources().getString(R.string.tags_add_title));

        // Create the tag dialog
        final View v = View.inflate(context, R.layout.tags_dialog, null);
        final MultiAutoCompleteTextView tagInput = v.findViewById(R.id.tag_input);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                android.R.layout.simple_dropdown_item_1line, KissApplication.getApplication(context).getDataHandler().getTagsHandler().getAllTagsAsArray());
        tagInput.setTokenizer(new SpaceTokenizer());
        tagInput.setText(pojo.getTags());

        tagInput.setAdapter(adapter);
        builder.setView(v);

        builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
            dialog.dismiss();
            // Refresh tags for given app
            pojo.setTags(tagInput.getText().toString());
            KissApplication.getApplication(context).getDataHandler().getTagsHandler().setTags(pojo.id, pojo.getTags());
            // Show toast message
            String msg = context.getResources().getString(R.string.tags_confirmation_added);
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.cancel());

        AlertDialog dialog = builder.create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);

        dialog.show();
    }

    private void launchUninstall(Context context, ShortcutPojo pojo) {
        DataHandler dh = KissApplication.getApplication(context).getDataHandler();
        if (pojo.isOreoShortcut() && pojo.isPinned()) {
            dh.unpinShortcut(pojo);
        } else {
            dh.removeShortcut(pojo);
        }
    }

    private void pinShortcut(Context context, ShortcutPojo pojo) {
        DataHandler dataHandler = KissApplication.getApplication(context).getDataHandler();
        dataHandler.pinShortcut(pojo);
    }

}
