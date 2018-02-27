/*
 * The MIT License
 *
 * Copyright (c) Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.redhat.jenkins.nodesharing;

import com.google.common.collect.Sets;
import com.redhat.jenkins.nodesharing.transport.ExecutorEntity;
import com.redhat.jenkins.nodesharing.transport.ReportUsageResponse;
import com.redhat.jenkins.nodesharingbackend.Api;
import com.redhat.jenkins.nodesharingbackend.ReservationTask;
import com.redhat.jenkins.nodesharingbackend.ReservationVerifier;
import com.redhat.jenkins.nodesharingfrontend.SharedNode;
import com.redhat.jenkins.nodesharingfrontend.SharedNodeCloud;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.model.queue.QueueTaskFuture;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.TestBuilder;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import static com.redhat.jenkins.nodesharingbackend.Pool.getInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public class ReportUsageTest {

    @Rule
    public NodeSharingJenkinsRule j = new NodeSharingJenkinsRule();

    @Rule
    public ConfigRepoRule configRepo = new ConfigRepoRule();

    @Test
    public void reportExecutorUsage() throws Exception {
        j.injectConfigRepo(configRepo.createReal(getClass().getResource("dummy_config_repo"), j.jenkins));
        SharedNodeCloud cloud = j.addSharedNodeCloud(getInstance().getConfigRepoUrl());
        cloud.getLatestConfig(); // NodeSharingComputerListener#preLaunch does not consider this to be operational until we have config

        ConfigRepo.Snapshot snapshot = getInstance().getConfig();
        Collection<NodeDefinition> declaredNodes = snapshot.getNodes().values();

        assertThat(j.getScheduledReservations(), emptyIterable());
        assertThat(j.getPendingReservations(), emptyIterable());
        assertThat(j.jenkins.getNodes(), Matchers.<Node>iterableWithSize(declaredNodes.size()));

        // Given all nodes shared for a single master
        for (NodeDefinition definition : declaredNodes) {
            SharedNode node = cloud.createNode(definition);
            j.jenkins.addNode(node);
            j.jenkins.getComputer(definition.getName()).waitUntilOnline();
        }
        assertThat(j.jenkins.getNodes(), Matchers.<Node>iterableWithSize(declaredNodes.size() * 2));

        for (int i = 0; i < 3; i++) {
            // When usage is reported
            ReservationVerifier.getInstance().doRun();

            // Then reservations are created
            assertThat(j.getPendingReservations().size(), equalTo(declaredNodes.size()));
            assertThat(j.getScheduledReservations(), emptyIterable());
        }

        // Cleanup to avoid all kinds of exceptions
        for (ReservationTask.ReservationExecutable executable : j.getPendingReservations()) {
            executable.complete();
        }
    }

    @Test
    public void orchestratorFailover() throws Exception {
        j.injectConfigRepo(configRepo.createReal(getClass().getResource("dummy_config_repo"), j.jenkins));
        SharedNodeCloud cloud = j.addSharedNodeCloud(getInstance().getConfigRepoUrl());

        ConfigRepo.Snapshot c = cloud.getLatestConfig();
        ArrayList<String> nodes = new ArrayList<>(c.getNodes().keySet());

        ConfigRepo.Snapshot config = spy(c);
        when(config.getJenkinses()).thenReturn(Sets.newHashSet(
                new ExecutorJenkins(j.jenkins.getRootUrl(), "Fake one"),
                new ExecutorJenkins(j.jenkins.getRootUrl(), "Fake two")
        ));
        Api api = mock(Api.class);
        ExecutorEntity.Fingerprint fp = new ExecutorEntity.Fingerprint("", "", "");
        when(api.reportUsage(Mockito.any(ExecutorJenkins.class))).thenReturn(
                new ReportUsageResponse(fp, Arrays.asList(nodes.get(0), nodes.get(1))),
                new ReportUsageResponse(fp, Collections.singletonList(nodes.get(3)))
        );

        ReservationVerifier.verify(config, api);

        assertThat(j.getScheduledReservations(), emptyIterable());
        assertThat(j.getPendingReservations(), Matchers.<ReservationTask.ReservationExecutable>iterableWithSize(3));
    }

    @Test
    public void missedReturnNodeCall() throws Exception {
        j.injectConfigRepo(configRepo.createReal(getClass().getResource("dummy_config_repo"), j.jenkins));
        SharedNodeCloud cloud = j.addSharedNodeCloud(getInstance().getConfigRepoUrl());
        ConfigRepo.Snapshot c = cloud.getLatestConfig();

        NodeDefinition node = c.getNodes().values().iterator().next();
        Label label = Label.get(node.getLabel().replaceAll("[ ]+", "&&"));
        FreeStyleProject project = j.createFreeStyleProject();
        project.setAssignedLabel(label);
        project.getBuildersList().add(new TestBuilder() {
            @Override public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
                ((Slave) build.getBuiltOn()).setRetentionStrategy(null); // deliberately cause the node to leak after build completion
                return true;
            }
        });
        QueueTaskFuture<FreeStyleBuild> f = project.scheduleBuild2(0);
        FreeStyleBuild build = f.get();

        assertNotEquals(null, build.getBuiltOn());

        // This does not invoke termination routine so returnNode is not sent
        j.jenkins.removeNode(build.getBuiltOn());
        assertEquals(1, j.getPendingReservations().size());
        Thread.sleep(5000); // Make sure it is not caused by timing
        assertEquals(1, j.getPendingReservations().size());

        ReservationVerifier.getInstance().doRun();
        Thread.sleep(1000);

        assertEquals(0, j.getPendingReservations().size());
    }
}
