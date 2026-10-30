package dev.aarchive;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import java.util.function.Supplier;

/** Keep selections and workers across rotation and system theme changes. */
final class ScreenModels {
    private ScreenModels() {}
    private static <T extends ViewModel> T retain(AppCompatActivity activity, Class<T> type, Supplier<T> factory) {
        return new ViewModelProvider(activity, new ViewModelProvider.Factory() {
            @Override public <V extends ViewModel> V create(Class<V> requested) { return requested.cast(factory.get()); }
        }).get(type);
    }
    static ArchiveModel archive(AppCompatActivity activity) {
        return retain(activity, ArchiveState.class, () -> new ArchiveState(activity)).model;
    }
    static CreationModel creation(AppCompatActivity activity) {
        return retain(activity, CreationState.class, () -> new CreationState(activity)).model;
    }
    private static final class ArchiveState extends ViewModel {
        final ArchiveModel model;
        ArchiveState(AppCompatActivity activity) { model = new ArchiveModel(activity); }
        @Override protected void onCleared() { model.close(); }
    }
    private static final class CreationState extends ViewModel {
        final CreationModel model;
        CreationState(AppCompatActivity activity) { model = new CreationModel(activity); }
        @Override protected void onCleared() { model.close(); }
    }
}
