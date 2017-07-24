package graphics.scenery.backends.opengl

import cleargl.GLShader
import cleargl.GLShaderType
import com.jogamp.opengl.GL4
import graphics.scenery.BufferUtils
import graphics.scenery.backends.vulkan.VulkanRenderer
import graphics.scenery.spirvcrossj.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.*
import kotlin.collections.LinkedHashMap


/**
 * Vulkan Object State class. Saves texture, UBO, pipeline and vertex buffer state.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
open class OpenGLShaderModule(gl: GL4, entryPoint: String, clazz: Class<*>, shaderCodePath: String) {
    protected var logger: Logger = LoggerFactory.getLogger("OpenGLShaderModule")
    var shader: GLShader
        private set
    var shaderType: GLShaderType
        private set
    var uboSpecs = LinkedHashMap<String, UBOSpec>()

    data class UBOMemberSpec(val name: String, val index: Long, val offset: Long, val range: Long)
    data class UBOSpec(val name: String, val set: Long, val binding: Long, val members: LinkedHashMap<String, UBOMemberSpec>)

    init {

        logger.debug("Creating OpenGLShaderModule $entryPoint, $shaderCodePath")

        // check if we have a compiled version, and it's newer than the source version
        val sourceClass: Class<*>
        var codeResource = if(clazz.javaClass.getResource(shaderCodePath) != null) {
            sourceClass = clazz
            clazz.javaClass.getResource(shaderCodePath)
        } else {
            sourceClass = VulkanRenderer::class.java
            VulkanRenderer::class.java.getResource(shaderCodePath)
        }

        val actualCodePath: String

        val sourceNewer = if(shaderCodePath.toLowerCase().endsWith("spv")) {
            val sourceCodeResource = sourceClass.getResource(shaderCodePath.substringBeforeLast(".spv"))

            if(sourceCodeResource != null) {
                // a slight bias is needed here, as if both files are compiled into the
                // classes/ or jar at the same time, they'll carry the same timestamp.
                val spirvModificationDate = if(codeResource != null) {
                    Date(codeResource.openConnection().lastModified + 500)
                } else {
                    Date(0)
                }

                val sourceModificationDate = Date(sourceCodeResource.openConnection().lastModified)

                if(sourceModificationDate.after(spirvModificationDate)) {
                    logger.info("Recompiling $shaderCodePath, as source file is newer than SPV file.")
                    actualCodePath = shaderCodePath.substringBeforeLast(".spv")
                    codeResource = sourceCodeResource
                    true
                } else {
                    actualCodePath = shaderCodePath
                    false
                }
            } else {
                actualCodePath = shaderCodePath
                false
            }
        } else {
            actualCodePath = shaderCodePath
            false
        }

        logger.debug("Reading shader from $actualCodePath...")

        var code = ByteBuffer.allocate(0)

        val spirv = if(shaderCodePath.toLowerCase().endsWith("spv") && !sourceNewer) {
            code = BufferUtils.allocateByteAndPut(codeResource.readBytes())
            code.toSPIRVBytecode()
        } else {
            logger.info("Compiling $actualCodePath to SPIR-V...")
            // code needs to be compiled first
            val program = TProgram()
            val defaultResources = libspirvcrossj.getDefaultTBuiltInResource()
            val shaderType = when (actualCodePath.substringAfterLast(".")) {
                "vert" -> EShLanguage.EShLangVertex
                "frag" -> EShLanguage.EShLangFragment
                "geom" -> EShLanguage.EShLangGeometry
                "tesc" -> EShLanguage.EShLangTessControl
                "tese" -> EShLanguage.EShLangTessEvaluation
                "comp" -> EShLanguage.EShLangCompute
                else -> { logger.warn("Unknown shader extension ." + actualCodePath.substringAfterLast(".")); 0 }
            }


            val shader = TShader(shaderType)

            var messages = EShMessages.EShMsgDefault
            messages = messages or EShMessages.EShMsgVulkanRules
            messages = messages or EShMessages.EShMsgSpvRules

            val shaderCode = arrayOf(codeResource.readText())
            shader.setStrings(shaderCode, shaderCode.size)
            shader.setAutoMapBindings(true)

            val compileFail = !shader.parse(defaultResources, 450, false, messages)
            if(compileFail) {
                logger.error("Error in shader compilation of $actualCodePath for ${clazz.simpleName}: ${shader.infoLog}")
            }

            program.addShader(shader)

            val linkFail = !program.link(EShMessages.EShMsgDefault) || !program.mapIO()

            if(!linkFail && !compileFail) {
                val tmp = IntVec()
                libspirvcrossj.glslangToSpv(program.getIntermediate(shaderType), tmp)

                code = tmp.toByteBuffer()

                tmp
            } else {
                logger.error("Error in shader linking of $actualCodePath for ${clazz.simpleName}: ${program.infoLog}")
                IntVec()
            }
        }

        val compiler = CompilerGLSL(spirv)

        val uniformBuffers = compiler.shaderResources.uniformBuffers

        for(i in 0..uniformBuffers.size()-1) {
            val res = uniformBuffers.get(i.toInt())
            logger.debug("${res.name}, set=${compiler.getDecoration(res.id, Decoration.DecorationDescriptorSet)}, binding=${compiler.getDecoration(res.id, Decoration.DecorationBinding)}")

            val members = LinkedHashMap<String, UBOMemberSpec>()
            val activeRanges = compiler.getActiveBufferRanges(res.id)

            // record all members of the UBO struct, order by index, and store them to UBOSpec.members
            // for further use
            members.putAll((0..activeRanges.size()-1).map {
                val range = activeRanges.get(it.toInt())
                val name = compiler.getMemberName(res.baseTypeId, range.index)

                name.to(UBOMemberSpec(
                    compiler.getMemberName(res.baseTypeId, range.index),
                    range.index,
                    range.offset,
                    range.range))
            }.sortedBy { it.second.index })

            val ubo = UBOSpec(res.name,
                set = compiler.getDecoration(res.id, Decoration.DecorationDescriptorSet),
                binding = compiler.getDecoration(res.id, Decoration.DecorationBinding),
                members = members)

            // only add the UBO spec if it doesn't already exist, and has more than 0 members
            // SPIRV UBOs may have 0 members, if they are not used in the actual shader code
            if(!uboSpecs.contains(res.name) && ubo.members.size > 0) {
                uboSpecs.put(res.name, ubo)
            }
        }

        /* Updated version:
       for(i in 0..compiler.shaderResources.sampledImages.size()-1) {
        // inputs are summarized into one descriptor set
            val res = compiler.shaderResources.sampledImages.get(i.toInt())
            logger.info("Adding textures ${res.name} with set=${compiler.getDecoration(res.id, Decoration.DecorationDescriptorSet)}")

            // FIXME: Here we assume at the moment that we either have only input textures (from framebuffers), or only object textures
            val name = if(res.name == "ObjectTextures" || res.name == "VolumeTextures") {
                "ObjectTextures"
            } else {
                "inputs"
            }

            uboSpecs.put(res.name, UBOSpec(name,
                    set = compiler.getDecoration(res.id, Decoration.DecorationDescriptorSet),
                    binding = 0,
                    members = LinkedHashMap<String, UBOMemberSpec>()))
         */
        // inputs are summarized into one descriptor set
        if(compiler.shaderResources.sampledImages.size() > 0) {
            val res = compiler.shaderResources.sampledImages.get(0)
            if (res.name != "ObjectTextures") {
                uboSpecs.put(res.name, UBOSpec("inputs",
                    set = compiler.getDecoration(res.id, Decoration.DecorationDescriptorSet),
                    binding = 0,
                    members = LinkedHashMap<String, UBOMemberSpec>()))
            }
        }

        val inputs = compiler.shaderResources.stageInputs
        if(inputs.size() > 0) {
            for (i in 0..inputs.size()-1) {
                logger.debug("$shaderCodePath: ${inputs.get(i.toInt()).name}")
            }
        }

        val options = CompilerGLSL.Options()
        options.version = 410
        options.es = false
        options.vulkanSemantics = false
        compiler.options = options

        val extension = if(actualCodePath.endsWith(".spv")) {
            actualCodePath.substringBeforeLast(".spv").substringAfterLast(".")
        } else {
            actualCodePath.substringAfterLast(".")
        }

        this.shaderType = toClearGLShaderType(extension)

        var source = compiler.compile()
        // remove binding and set qualifiers
        var start = 0
        var found = source.indexOf("layout(", start)
        while(found != -1) {
            logger.debug("Found match at $found with start index $start")
            start = found + 7
            val end = source.indexOf(")", start) + 1

            if(source.substring(end, source.indexOf(";", end)).contains(" in ")) {
                logger.debug("Not touching input layouts")
                start = end
                found = source.indexOf("layout(", start)
                continue
            }

            if(source.substring(end, source.indexOf(";", end)).contains(" out ")) {
                logger.debug("Not touching output layouts")
                start = end
                found = source.indexOf("layout(", start)
                continue
            }

            if(source.substring(end, source.indexOf(";", end)).contains("sampler")) {
                logger.debug("Converting sampler UBO to uniform")
                source = source.replaceRange(start-7, end, "")
                start = found
                found = source.indexOf("layout(", start)
                continue
            }

            if(source.substring(end, source.indexOf(";", end)).contains("vec")) {
                logger.debug("Converting location-based struct to regular struct")
                source = source.replaceRange(start-7, end, "")
                start = found
                found = source.indexOf("layout(", start)
                continue
            }


            if(source.substring(start, end).contains("set") || source.substring(start, end).contains("binding")) {
                logger.debug("Replacing ${source.substring(start, end)}")
                source = source.replaceRange(start-7, end, "layout(std140)")
                start = found + 15
            } else {
                logger.debug("Skipping ${source.substring(start, end)}")
                start = end
            }

            found = source.indexOf("layout(", start)
        }

        this.shader = GLShader(gl, source, toClearGLShaderType(extension))

        if(this.shader.shaderInfoLog.isNotEmpty()) {
            logger.warn("Shader compilation log:")
            logger.warn(this.shader.shaderInfoLog)
        }
    }

    private fun IntVec.toByteBuffer(): ByteBuffer {
        val buf = BufferUtils.allocateByte(this.size().toInt()*4)
        val ib = buf.asIntBuffer()

        for (i in 0..this.size()-1) {
            ib.put(this[i.toInt()].toInt())
        }

        return buf
    }

    private fun ByteBuffer.toSPIRVBytecode(): IntVec {
        val bytecode = IntVec()
        val ib = this.asIntBuffer()

        while(ib.hasRemaining()) {
            bytecode.pushBack(1L*ib.get())
        }

        return bytecode
    }

    private fun toClearGLShaderType(extension: String): GLShaderType {
        return when(extension) {
            "vert" -> GLShaderType.VertexShader
            "frag" -> GLShaderType.FragmentShader
            "tesc" -> GLShaderType.TesselationControlShader
            "tese" -> GLShaderType.TesselationEvaluationShader
            "geom" -> GLShaderType.GeometryShader
            "comp" -> GLShaderType.ComputeShader
            else -> {
                logger.error("Unknown shader type: $extension")
                GLShaderType.VertexShader
            }
        }
    }
}
