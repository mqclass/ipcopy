package ru.mqclass.ipcopy.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import ru.mqclass.ipcopy.gui.IpCopyScreen;

/**
 * ModMenu integration for IP Copy.
 * Safely registers native configuration screen in ModMenu UI.
 */
public class ModMenuCompat implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return IpCopyScreen::new;
    }
}
