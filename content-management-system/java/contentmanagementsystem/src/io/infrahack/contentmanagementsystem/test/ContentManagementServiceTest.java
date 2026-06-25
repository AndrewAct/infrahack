package io.infrahack.contentmanagementsystem.test;

import io.infrahack.contentmanagementsystem.enums.AssetStatus;
import io.infrahack.contentmanagementsystem.enums.AssetType;
import io.infrahack.contentmanagementsystem.enums.LifecycleStatus;
import io.infrahack.contentmanagementsystem.enums.Role;
import io.infrahack.contentmanagementsystem.model.*;
import io.infrahack.contentmanagementsystem.repository.ContentRepository;
import io.infrahack.contentmanagementsystem.repository.InMemoryContentRepository;
import io.infrahack.contentmanagementsystem.service.*;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertTrue;

public class ContentManagementServiceTest {
    @Test
    void editorCanCreateAndAddMetadata() {
        ContentRepository repo = new InMemoryContentRepository();
        PermissionService permissions = new PermissionService();
        AuditService audit = new AuditService();
        MetricsCollector metrics = new MetricsCollector();

        ContentManagementService service =
                new ContentManagementService(repo, permissions, audit, metrics);

        User editor = new User("e1", "Editor", Set.of(Role.EDITOR));
        Movie movie = new Movie("m1", "Roma", Duration.ofMinutes(135));

        service.create(movie, editor);
        service.addMetadata("m1", new LocalizedMetadata(
                Locale.US, "Roma", "Drama"
        ), editor);

        assertTrue(repo.findById("m1").isPresent());
        assertEquals(1, metrics.count("content.metadata.added"));
    }

    @Test
    void publisherCanPublishValidMovie() {
        ContentRepository repo = new InMemoryContentRepository();
        PermissionService permissions = new PermissionService();
        AuditService audit = new AuditService();
        MetricsCollector metrics = new MetricsCollector();

        Movie movie = new Movie("m1", "Roma", Duration.ofMinutes(135));
        movie.addMetadata(new LocalizedMetadata(Locale.US, "Roma", "Drama"));
        movie.addAsset(new MediaAsset("a1", AssetType.VIDEO, Locale.US, "s3://video", AssetStatus.VALIDATED));
        movie.transitionTo(LifecycleStatus.IN_REVIEW);
        movie.transitionTo(LifecycleStatus.APPROVED);
        repo.save(movie, 0);

        PublishService service = new PublishService(repo, permissions, audit, metrics);
        User publisher = new User("p1", "Publisher", Set.of(Role.PUBLISHER));

        ContentSnapshot snapshot = service.publish("m1", publisher);

        assertEquals(LifecycleStatus.PUBLISHED, snapshot.lifecycleStatus());
        assertEquals(1, metrics.count("content.published"));
    }
}

