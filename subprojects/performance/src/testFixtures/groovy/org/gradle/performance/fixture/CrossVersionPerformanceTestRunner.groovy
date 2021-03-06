/*
 * Copyright 2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.performance.fixture

import org.gradle.integtests.fixtures.executer.GradleDistribution
import org.gradle.integtests.fixtures.executer.IntegrationTestBuildContext
import org.gradle.integtests.fixtures.versions.ReleasedVersionDistributions
import org.gradle.internal.jvm.Jvm
import org.gradle.internal.os.OperatingSystem
import org.gradle.performance.measure.Amount
import org.gradle.performance.measure.DataAmount
import org.gradle.performance.measure.Duration
import org.gradle.util.GradleVersion

public class CrossVersionPerformanceTestRunner extends PerformanceTestSpec {
    GradleDistribution current
    final IntegrationTestBuildContext buildContext = new IntegrationTestBuildContext()
    final DataReporter<CrossVersionPerformanceResults> reporter
    TestProjectLocator testProjectLocator = new TestProjectLocator()
    final BuildExperimentRunner experimentRunner

    String testProject
    boolean useDaemon

    List<String> tasksToRun = []
    List<String> args = []
    List<String> gradleOpts = ['-Xms2g', '-Xmx2g', '-XX:MaxPermSize=256m']
    List<String> previousTestIds = []

    List<String> targetVersions = []
    Amount<Duration> maxExecutionTimeRegression = Duration.millis(0)
    Amount<DataAmount> maxMemoryRegression = DataAmount.bytes(0)

    BuildExperimentListener buildExperimentListener

    CrossVersionPerformanceTestRunner(BuildExperimentRunner experimentRunner, DataReporter<CrossVersionPerformanceResults> reporter) {
        this.reporter = reporter
        this.experimentRunner = experimentRunner
    }

    CrossVersionPerformanceResults run() {
        if (testId == null) {
            throw new IllegalStateException("Test id has not been specified")
        }
        if (testProject == null) {
            throw new IllegalStateException("Test project has not been specified")
        }
        if (!targetVersions) {
            throw new IllegalStateException("Target versions have not been specified")
        }

        def results = new CrossVersionPerformanceResults(
            testId: testId,
            previousTestIds: previousTestIds.collect { it.toString() }, // Convert GString instances
            testProject: testProject,
            tasks: tasksToRun.collect { it.toString() },
            args: args.collect { it.toString() },
            gradleOpts: gradleOpts.collect { it.toString() },
            daemon: useDaemon,
            jvm: Jvm.current().toString(),
            operatingSystem: OperatingSystem.current().toString(),
            versionUnderTest: GradleVersion.current().getVersion(),
            vcsBranch: Git.current().branchName,
            vcsCommits: [Git.current().commitId],
            testTime: System.currentTimeMillis())

        def releasedDistributions = new ReleasedVersionDistributions()
        def mostRecentFinalRelease = releasedDistributions.mostRecentFinalRelease.version.version
        def mostRecentSnapshot = releasedDistributions.mostRecentSnapshot.version.version
        def currentBaseVersion = GradleVersion.current().getBaseVersion().version
        def baselineVersions = targetVersions.findAll { it != 'last' && it != 'nightly' } as LinkedHashSet

        if (!targetVersions.find { it == 'nightly'}) {
            // Include the most recent final release if we're not testing against a nightly
            baselineVersions.add(mostRecentFinalRelease)
        } else {
            baselineVersions.add(mostRecentSnapshot)
        }

        // A target version may be something that is yet unreleased, so filter that out
        baselineVersions.remove(currentBaseVersion)

        File projectDir = testProjectLocator.findProjectDir(testProject)

        baselineVersions.each { it ->
            def baselineVersion = results.baseline(it)
            baselineVersion.maxExecutionTimeRegression = maxExecutionTimeRegression
            baselineVersion.maxMemoryRegression = maxMemoryRegression

            runVersion(buildContext.distribution(baselineVersion.version), projectDir, baselineVersion.results)
        }

        runVersion(current, projectDir, results.current)

        results.assertEveryBuildSucceeds()
        results.assertCurrentVersionHasNotRegressed()

        // Don't store results when builds have failed
        reporter.report(results)

        return results
    }

    private void runVersion(GradleDistribution dist, File projectDir, MeasuredOperationList results) {
        def builder = BuildExperimentSpec.builder()
            .projectName(testId)
            .displayName(dist.version.version)
            .warmUpCount(warmUpRuns)
            .invocationCount(runs)
            .listener(buildExperimentListener)
            .invocation {
            workingDirectory(projectDir)
            distribution(dist)
            tasksToRun(this.tasksToRun as String[])
            args(this.args as String[])
            gradleOpts(this.gradleOpts as String[])
            useDaemon(this.useDaemon)
        }

        def spec = builder.build()

        experimentRunner.run(spec, results)
    }

}
