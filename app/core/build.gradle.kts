// 차량 신호 → 구간·정산 계산. Android 의존성 없이 JVM에서 테스트한다.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
