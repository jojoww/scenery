package graphics.scenery.tests.examples

import cleargl.GLVector
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.controls.InputHandler
import graphics.scenery.controls.OpenVRHMD
import graphics.scenery.controls.behaviours.ArcballCameraControl
import graphics.scenery.controls.behaviours.FPSCameraControl
import graphics.scenery.volumes.DirectVolume
import graphics.scenery.volumes.DirectVolumeFullscreen
import graphics.scenery.volumes.Volume
import org.junit.Test
import org.scijava.ui.behaviour.ClickBehaviour
import java.io.File
import java.nio.file.Paths
import kotlin.concurrent.thread

/**
 * <Description>
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class VolumeExample: SceneryDefaultApplication("Volume Rendering example") {
    var hmd: OpenVRHMD? = null

    override fun init() {
        hmd = OpenVRHMD(useCompositor = true)
        hub.add(SceneryElement.HMDInput, hmd!!)

        renderer = Renderer.createRenderer(hub, applicationName, scene, 2560, 1600)
        hub.add(SceneryElement.Renderer, renderer!!)

        val cam: Camera = DetachedHeadCamera(hmd)
        with(cam) {
            position = GLVector(0.0f, 0.0f, 5.0f)
            perspectiveCamera(50.0f, 1.0f*windowWidth, 1.0f*windowHeight)
            active = true

            scene.addChild(this)
        }

        val shell = Box(GLVector(120.0f, 120.0f, 120.0f), insideNormals = true)
        shell.material.doubleSided = true
        shell.material.diffuse = GLVector(1.0f, 1.0f, 1.0f)
        shell.material.specular = GLVector.getNullVector(3)
        shell.material.ambient = GLVector.getNullVector(3)
        scene.addChild(shell)

        val volume = DirectVolumeFullscreen()

        with(volume) {
            scene.addChild(this)
        }

        val lights = (0..3).map {
            PointLight()
        }

        lights.mapIndexed { i, light ->
            light.position = GLVector(4.0f * i, 4.0f * i, 4.0f)
            light.emissionColor = GLVector(1.0f, 1.0f, 1.0f)
            light.intensity = 500.2f*(i+1)
            light.linear = 1.8f
            light.quadratic = 0.7f
            scene.addChild(light)
        }

        val folder = File("F:/ExampleDatasets/xwing-isonet-drosophila")
        val files = folder.listFiles()
        val volumes = files.filter { System.err.println(it); it.isFile && it.name.endsWith("raw") }.map { it.absolutePath }

        var currentVolume = 0
        fun nextVolume(): String {
            val v = volumes[currentVolume % (volumes.size - 1)]
            currentVolume++

            return v
        }

        thread {
            while(!scene.initialized) { Thread.sleep(200) }

            while(true) {
                Thread.sleep(600)

                logger.info("Reading next volume...")
                volume.readFrom(Paths.get(nextVolume()), replace = true)
            }
        }

        thread {
            while(true) {
                volume.rotation.rotateByAngleY(0.01f)
                volume.needsUpdate = true

                Thread.sleep(20)
            }
        }

    }

    override fun inputSetup() {
        val target = GLVector(1.5f, 5.5f, 55.5f)
        val inputHandler = (hub.get(SceneryElement.Input) as InputHandler)
        val targetArcball = ArcballCameraControl("mouse_control", scene.findObserver(), renderer!!.window.width, renderer!!.window.height, target)
        val fpsControl = FPSCameraControl("mouse_control", scene.findObserver(), renderer!!.window.width, renderer!!.window.height)

        val toggleControlMode = object : ClickBehaviour {
            var currentMode = "fps"

            override fun click(x: Int, y: Int) {
                if (currentMode.startsWith("fps")) {
                    targetArcball.target = GLVector(0.0f, 0.0f, 0.0f)

                    inputHandler.addBehaviour("mouse_control", targetArcball)
                    inputHandler.addBehaviour("scroll_arcball", targetArcball)
                    inputHandler.addKeyBinding("scroll_arcball", "scroll")

                    currentMode = "arcball"
                } else {
                    inputHandler.addBehaviour("mouse_control", fpsControl)
                    inputHandler.removeBehaviour("scroll_arcball")

                    currentMode = "fps"
                }

                System.out.println("Switched to $currentMode control")
            }
        }

        inputHandler.addBehaviour("toggle_control_mode", toggleControlMode)
        inputHandler.addKeyBinding("toggle_control_mode", "C")
    }

    @Test override fun main() {
        super.main()
    }
}
