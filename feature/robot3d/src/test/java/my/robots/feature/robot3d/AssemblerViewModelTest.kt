package my.robots.feature.robot3d

import org.junit.Assert.assertEquals
import org.junit.Test

class AssemblerViewModelTest {

    @Test
    fun pasta_do_robo_salvo() {
        assertEquals("kj264_cabine_2", my.robots.core.render3d.RobotLibrary.slug("KJ264 · Cabine 2"))
        assertEquals("robo_acao", my.robots.core.render3d.RobotLibrary.slug("Robô ação"))
        assertEquals("robo", my.robots.core.render3d.RobotLibrary.slug("///"))
    }
}
