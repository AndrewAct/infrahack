package io.infrahack.contentmanagementsystem;

import io.infrahack.contentmanagementsystem.enums.AssetStatus;
import io.infrahack.contentmanagementsystem.enums.AssetType;
import io.infrahack.contentmanagementsystem.enums.LifecycleStatus;
import io.infrahack.contentmanagementsystem.enums.Role;
import io.infrahack.contentmanagementsystem.factory.ContentFactory;
import io.infrahack.contentmanagementsystem.model.*;
import io.infrahack.contentmanagementsystem.repository.ContentRepository;
import io.infrahack.contentmanagementsystem.repository.InMemoryContentRepository;
import io.infrahack.contentmanagementsystem.service.*;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

public class Main {
    public static void main(String[] args) {
        ContentRepository repo = new InMemoryContentRepository();
        PermissionService permissions = new PermissionService();
        AuditService audit = new AuditService();
        MetricsCollector metrics = new MetricsCollector();

        ContentFactory factory = new ContentFactory();
        ContentManagementService cms = new ContentManagementService(repo, permissions, audit, metrics);
        PublishService publishing = new PublishService(repo, permissions, audit, metrics);

        User admin = new User("u1", "Admin", Set.of(Role.ADMIN));

        Movie movie = factory.createMovie("Example Movie", Duration.ofMinutes(120));
        cms.create(movie, admin);

        cms.addMetadata(movie.id(), new LocalizedMetadata(
                Locale.US, "Example Movie", "A sample title."
        ), admin);

        movie.addAsset(new MediaAsset(
                "asset1", AssetType.VIDEO, Locale.US, "s3://bucket/movie.mp4", AssetStatus.VALIDATED
        ));

        movie.transitionTo(LifecycleStatus.IN_REVIEW);
        movie.transitionTo(LifecycleStatus.APPROVED);

        ContentSnapshot snapshot = publishing.publish(movie.id(), admin);
        System.out.println("Published snapshot: " + snapshot);
        System.out.println("Metrics published=" + metrics.count("content.published"));
        System.out.println("Audit=" + audit.events());
    }
}