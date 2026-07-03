package com.idfcfirstbank.integration.platform.opsquery;

import com.idfcfirstbank.integration.platform.opsquery.domain.OpsRun;
import com.idfcfirstbank.integration.platform.opsquery.domain.OpsRunStore;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test harness standing in for the ENGINE: provides the {@link OpsRunStore}
 * bean (which activates the ops auto-configuration) backed by a seedable list.
 */
@SpringBootApplication
public class OpsQueryTestApp {

    public static class SeedableOpsRunStore implements OpsRunStore {

        public final List<OpsRun> runs = new CopyOnWriteArrayList<>();

        @Override
        public Optional<OpsRun> find(String runId) {
            return runs.stream().filter(r -> r.runId().equals(runId)).findFirst();
        }

        @Override
        public List<OpsRun> scanAll() {
            return List.copyOf(runs);
        }
    }

    @Bean
    SeedableOpsRunStore opsRunStore() {
        return new SeedableOpsRunStore();
    }
}
