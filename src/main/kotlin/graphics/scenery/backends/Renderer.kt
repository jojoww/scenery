package graphics.scenery.backends

import graphics.scenery.Hub
import graphics.scenery.Hubable
import graphics.scenery.Scene
import graphics.scenery.Settings
import graphics.scenery.backends.opengl.OpenGLRenderer
import graphics.scenery.backends.vulkan.VulkanRenderer
import graphics.scenery.utils.SceneryPanel

/**
 * Renderer interface. Defines the minimal set of functions a renderer has to implement.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
interface Renderer : Hubable {
    /**
     * This function should initialize the scene contents.
     *
     * @param[scene] The scene to initialize.
     */
    fun initializeScene()

    /**
     * This function renders the scene
     *
     * @param[scene] The scene to render.
     */
    fun render()

    var shouldClose: Boolean

    var settings: Settings

    var window: SceneryWindow

    var embedIn: SceneryPanel?

    fun close()

    fun screenshot()

    fun reshape(newWidth: Int, newHeight: Int)

    val managesRenderLoop: Boolean

    companion object Factory {
        @JvmOverloads fun createRenderer(hub: Hub, applicationName: String, scene: Scene, windowWidth: Int, windowHeight: Int, embedIn: SceneryPanel? = null): Renderer {
            val preference = System.getProperty("scenery.Renderer", "OpenGLRenderer")

            return if (preference == "VulkanRenderer") {
                VulkanRenderer(hub, applicationName, scene, windowWidth, windowHeight, embedIn)
            } else {
                OpenGLRenderer(hub, applicationName, scene, windowWidth, windowHeight, embedIn)
            }
        }
    }
}
