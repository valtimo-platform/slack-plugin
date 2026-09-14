/*
 * Copyright 2015-2023 Ritense BV, the Netherlands.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

val kotlinLoggingVersion: String by project
val okhttpVersion: String by project
val mockitoKotlinVersion: String by project
val operatonVersion: String by project

dockerCompose {
    setProjectName("slack")
    isRequiredBy(project.tasks.test)

    tasks.test {
        useComposeFiles.addAll("$rootDir/docker-resources/docker-compose-base-test.yml")
    }
}

dependencies {
    compileOnly("com.ritense.valtimo:authorization")
    compileOnly("com.ritense.valtimo:case")
    compileOnly("com.ritense.valtimo:contract")
    compileOnly("com.ritense.valtimo:core")
    compileOnly("com.ritense.valtimo:plugin-valtimo")
    compileOnly("com.ritense.valtimo:process-document")
    compileOnly("com.ritense.valtimo:temporary-resource-storage")
    compileOnly("com.ritense.valtimo:value-resolver")
    compileOnly("org.operaton.bpm:operaton-engine:$operatonVersion")

    compileOnly("org.springframework.boot:spring-boot-starter-webflux")
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework.boot:spring-boot-starter-data-jpa")

    // For @SchedulerLock on the poller. compileOnly like the Valtimo modules: a Valtimo
    // application already has ShedLock on its classpath, because core's
    // SchedulerAutoConfiguration builds the JdbcTemplateLockProvider the annotation needs.
    compileOnly("net.javacrumbs.shedlock:shedlock-spring")

    compileOnly("com.fasterxml.jackson.core:jackson-databind")
    compileOnly("io.github.oshai:kotlin-logging-jvm:$kotlinLoggingVersion")

    // Testing
    testImplementation("com.ritense.valtimo:authorization")
    testImplementation("com.ritense.valtimo:building-block")
    testImplementation("com.ritense.valtimo:contract")
    testImplementation("com.ritense.valtimo:core")
    testImplementation("com.ritense.valtimo:plugin")
    testImplementation("com.ritense.valtimo:temporary-resource-storage")
    testImplementation("com.ritense.valtimo:test-utils-common")

    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
    testImplementation("org.springframework.boot:spring-boot-starter-test")

    testImplementation("org.postgresql:postgresql")

    testImplementation("com.ritense.valtimo:plugin-valtimo")
    testImplementation("com.ritense.valtimo:value-resolver")

    testImplementation("org.mockito.kotlin:mockito-kotlin:$mockitoKotlinVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("com.squareup.okhttp3:mockwebserver:$okhttpVersion")
    testImplementation("com.ritense.valtimo:case")
    testImplementation("com.ritense.valtimo:process-document")
}

apply(from = "gradle/publishing.gradle")
