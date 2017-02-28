#version 450 core
#extension GL_ARB_separate_shader_objects: enable

layout(location = 0) in vec3 vertexPosition;
layout(location = 1) in vec3 vertexNormal;
layout(location = 2) in vec2 vertexTexCoord;

layout(location = 0) out VertexData {
    vec3 FragPosition;
    vec3 Normal;
    vec2 TexCoord;
} VertexOut;

layout(binding = 0) uniform Matrices {
	mat4 ModelMatrix;
	mat4 ViewMatrix;
	mat4 NormalMatrix;
	mat4 ProjectionMatrix;
	vec3 CamPosition;
	int isBillboard;
} ubo;

layout(set = 2, binding = 0) uniform VRParameters {
    mat4 projectionMatrices[2];
    mat4 headShift;
    float IPD;
    int stereoEnabled;
} vrParameters;

layout(push_constant) uniform currentEye_t {
    int eye;
} currentEye;

void main()
{
	mat4 mv;
	mat4 nMVP;
	mat4 projectionMatrix;

    mat4 headToEye = vrParameters.headShift;
	headToEye[3][0] += currentEye.eye * vrParameters.IPD;

    mv = (vrParameters.stereoEnabled ^ 1) * ubo.ViewMatrix * ubo.ModelMatrix + (vrParameters.stereoEnabled * ubo.ViewMatrix * (ubo.ModelMatrix * headToEye));
	projectionMatrix = (vrParameters.stereoEnabled ^ 1) * ubo.ProjectionMatrix + vrParameters.stereoEnabled * vrParameters.projectionMatrices[currentEye.eye];

	if(ubo.isBillboard > 0) {
		mv[0][0] = 1.0f;
		mv[0][1] = .0f;
		mv[0][2] = .0f;

		mv[1][0] = .0f;
		mv[1][1] = 1.0f;
		mv[1][2] = .0f;

		mv[2][0] = .0f;
		mv[2][1] = .0f;
		mv[2][2] = 1.0f;
	}

	nMVP = projectionMatrix*mv;

    VertexOut.Normal = mat3(ubo.NormalMatrix) * normalize(vertexNormal);
    VertexOut.TexCoord = vertexTexCoord;
    VertexOut.FragPosition = vec3(ubo.ModelMatrix * vec4(vertexPosition, 1.0));

	gl_Position = nMVP * vec4(vertexPosition, 1.0);
}

