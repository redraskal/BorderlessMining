package link.infra.borderlessmining.dxgl;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.COM.COMUtils;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.ptr.PointerByReference;
import link.infra.dxjni.*;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.util.Window;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.WGLNVDXInterop;

public class DXGLWindow {
	public D3D11Texture2D dxColorBuffer = null;
	public int colorRenderbuffer = 0;
	public int targetFramebuffer = 0;
	public long d3dDevice = 0;
	public long d3dDeviceGl = 0;
	public DXGISwapchain d3dSwapchain = null;
	public long d3dContext = 0;
	public final PointerBuffer d3dTargets = PointerBuffer.allocateDirect(1);

	private final long handle;
	private final Window parent;

	private enum RenderQueueSkipState {
		NONE,
		SKIP_LAST_DRAW_AND_QUEUE,
		SKIP_QUEUE
	}

	private RenderQueueSkipState skipRenderQueue = RenderQueueSkipState.NONE;

	public DXGLWindow(Window parent) {
		this.parent = parent;

		// TODO: attach/detach from existing window?
		GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
		handle = GLFW.glfwCreateWindow(640, 480, "DXGL offscreen GL context", 0, 0);
	}

	/**
	 * Make the GL context of this window current (instead of the parent window)
	 */
	public void makeCurrent() {
		GLFW.glfwMakeContextCurrent(handle);

		// Set up d3d in onscreen context
		long hWnd = GLFWNativeWin32.glfwGetWin32Window(parent.getHandle());

		DXGISwapChainDesc desc = new DXGISwapChainDesc();
		// Width/Height/RefreshRate inferred from window/monitor
		desc.BufferDesc.Format.setValue(DXGIModeDesc.DXGI_FORMAT_R8G8B8A8_UNORM);
		// Default sampler mode (no multisampling)
		desc.SampleDesc.Count.setValue(1);

		desc.BufferUsage.setValue(DXGISwapChainDesc.DXGI_USAGE_RENDER_TARGET_OUTPUT);
		desc.BufferCount.setValue(2);
		desc.OutputWindow.setPointer(new Pointer(hWnd));
		desc.Windowed.setValue(1);
		// TODO: backcompat? FLIP_DISCARD is only w10+
		desc.SwapEffect.setValue(DXGISwapChainDesc.DXGI_SWAP_EFFECT_FLIP_DISCARD);
		// TODO: feature test allow tearing
		desc.Flags.setValue(DXGISwapChainDesc.DXGI_SWAP_CHAIN_FLAG_ALLOW_TEARING);

		PointerByReference d3dSwapchainRef = new PointerByReference();
		PointerByReference deviceRef = new PointerByReference();
		PointerByReference contextRef = new PointerByReference();

		COMUtils.checkRC(D3D11Library.INSTANCE.D3D11CreateDeviceAndSwapChain(
			Pointer.NULL, // No adapter
			new WinDef.UINT(D3D11Library.D3D_DRIVER_TYPE_HARDWARE), new WinDef.HMODULE(), // Use hardware driver (no software DLL)
			new WinDef.UINT(0), //DXJNIShim.D3D11_CREATE_DEVICE_DEBUG, // Debug flag TODO: make dependent on something else
			Pointer.NULL, // Use default feature levels
			new WinDef.UINT(0),
			D3D11Library.D3D11_SDK_VERSION,
			desc,
			d3dSwapchainRef,
			deviceRef,
			new WinDef.UINTByReference(), // No need to get used feature level
			contextRef
		));

		// TODO: wrapper class?
		d3dDevice = Pointer.nativeValue(deviceRef.getValue());
		d3dSwapchain = new DXGISwapchain(d3dSwapchainRef.getValue());
		d3dContext = Pointer.nativeValue(contextRef.getValue());

		PointerByReference colorBufferBuf = new PointerByReference();
		// Get swapchain backbuffer as an ID3D11Texture2D
		COMUtils.checkRC(d3dSwapchain.GetBuffer(
			new WinDef.UINT(0),
			new Guid.REFIID(D3D11Texture2D.IID_ID3D11Texture2D),
			colorBufferBuf
		));
		dxColorBuffer = new D3D11Texture2D(colorBufferBuf.getValue());
	}

	/**
	 * Initialise GL-dependent context (i.e. WGLNVDXInterop); must be run after makeCurrent and GL.createCapabilities
	 */
	public void initGL() {
		d3dDeviceGl = WGLNVDXInterop.wglDXOpenDeviceNV(d3dDevice);
		// TODO: this can return 0 (maybe if the d3d+gl adapters don't match?)

		targetFramebuffer = GL32C.glGenFramebuffers();
		colorRenderbuffer = GL32C.glGenRenderbuffers();
	}

	public void present(boolean vsync) {
		// TODO: option for >1 vsync
		// TODO: tearing
		// DXGI_PRESENT_ALLOW_TEARING can only be used with sync interval 0. It is recommended to always pass this
		// tearing flag when using sync interval 0 if CheckFeatureSupport reports that tearing is supported and the app
		// is in a windowed mode - including border-less fullscreen mode. Refer to the DXGI_PRESENT constants for more details.
		// TODO: could use blt model if unthrottled framerate is desired and tearing is not supported

		// TODO: adaptive vsync by detecting when a frame is skipped (using present stats) and presenting newest frame?
		// TODO: could look into Special K's Always Present Newest Frame
		// TODO: https://developer.nvidia.com/dx12-dos-and-donts#swapchains

		// Present frame (using DXGI instead of OpenGL)
		int syncInterval = vsync ? 1 : 0;
		int flags = vsync ? 0 : DXJNIShim.DXGI_PRESENT_ALLOW_TEARING;
		if (skipRenderQueue == RenderQueueSkipState.SKIP_LAST_DRAW_AND_QUEUE) {
			// Don't present (force new draw)
			// TODO: does this work with framerate limiter?
			return;
		} else if (skipRenderQueue == RenderQueueSkipState.SKIP_QUEUE) {
			// Discard stale queued presents (before resize)
			flags |= DXJNIShim.DXGI_PRESENT_RESTART;
			skipRenderQueue = RenderQueueSkipState.NONE;
		}
		// TODO: feature test allow tearing
		d3dSwapchain.Present(new WinDef.UINT(syncInterval), new WinDef.UINT(flags));
	}

	public void resize(int width, int height) {
		// TODO: use WindowResolutionChangeWrapper?
		dxColorBuffer.Release();
		COMUtils.checkRC(d3dSwapchain.ResizeBuffers(
			// TODO: configurable buffer count?
			new WinDef.UINT(2),
			new WinDef.UINT(width),
			new WinDef.UINT(height),
			new WinDef.UINT(DXGIModeDesc.DXGI_FORMAT_R8G8B8A8_UNORM),
			// TODO: feature test allow tearing
			new WinDef.UINT(DXGISwapChainDesc.DXGI_SWAP_CHAIN_FLAG_ALLOW_TEARING)
		));

		PointerByReference colorBufferBuf = new PointerByReference();
		// Get swapchain backbuffer as an ID3D11Texture2D
		COMUtils.checkRC(d3dSwapchain.GetBuffer(
			new WinDef.UINT(0),
			new Guid.REFIID(D3D11Texture2D.IID_ID3D11Texture2D),
			colorBufferBuf
		));
		dxColorBuffer = new D3D11Texture2D(colorBufferBuf.getValue());

		skipRenderQueue = RenderQueueSkipState.SKIP_LAST_DRAW_AND_QUEUE;
	}

	// TODO: move to some sort of swapchain class?
	public void draw(Framebuffer instance, int width, int height) {
		// TODO: waitable object?

		// Register d3d backbuffer as an OpenGL renderbuffer
		d3dTargets.put(WGLNVDXInterop.wglDXRegisterObjectNV(
			d3dDeviceGl, Pointer.nativeValue(dxColorBuffer.getPointer()), colorRenderbuffer,
			GL32C.GL_RENDERBUFFER, WGLNVDXInterop.WGL_ACCESS_WRITE_DISCARD_NV));
		d3dTargets.flip();

		// Set up framebuffer and attach d3d backbuffer
		WGLNVDXInterop.wglDXLockObjectsNV(d3dDeviceGl, d3dTargets);
		GL32C.glBindFramebuffer(GL32C.GL_FRAMEBUFFER, targetFramebuffer);
		GL32C.glFramebufferRenderbuffer(GL32C.GL_FRAMEBUFFER, GL32C.GL_COLOR_ATTACHMENT0, GL32C.GL_RENDERBUFFER, colorRenderbuffer);

		// Draw frame
		instance.draw(width, height);

		// Unwind: unbind the framebuffer, unlock and unregister backbuffer
		GL32C.glBindFramebuffer(GL32C.GL_FRAMEBUFFER, 0);
		WGLNVDXInterop.wglDXUnlockObjectsNV(d3dDeviceGl, d3dTargets);
		WGLNVDXInterop.wglDXUnregisterObjectNV(d3dDeviceGl, d3dTargets.get());
		d3dTargets.flip();

		if (skipRenderQueue == RenderQueueSkipState.SKIP_LAST_DRAW_AND_QUEUE) {
			// Have drawn since last skip; current backbuffer is valid
			skipRenderQueue = RenderQueueSkipState.SKIP_QUEUE;
		}
	}
}
