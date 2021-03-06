package com.meshcentral.agent

import android.Manifest
import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.navigation.fragment.findNavController
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionDeniedResponse
import com.karumi.dexter.listener.PermissionGrantedResponse
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import com.karumi.dexter.listener.single.PermissionListener

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class MainFragment : Fragment(), MultiplePermissionsListener {
    var alert : AlertDialog? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.main_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mainFragment = this
        visibleScreen = 1;

        refreshInfo()

        view.findViewById<Button>(R.id.agentActionButton).setOnClickListener {
            var serverLink = serverLink;
            if (serverLink == null) {
                // Setup the server
                findNavController().navigate(R.id.action_FirstFragment_to_SecondFragment)
            } else {
                if ((activity as MainActivity).isAgentDisconnected() == false) {
                    (activity as MainActivity).toggleAgentConnection()
                } else {
                    // Perform action on the agent
                    Dexter.withContext(context)
                        .withPermissions(
                            Manifest.permission.CAMERA,
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        )
                        .withListener(this)
                        .check()
                }
            }
        }

        // Check if the app was called using a URL link
        if ((activity != null) && ((activity as MainActivity).intent != null) && ((activity as MainActivity).intent.data != null)) {
            var data: Uri? = (activity as MainActivity).intent.data;
            if (data != null && data.isHierarchical()) {
                var uri: String? = (activity as MainActivity).intent.dataString;
                if ((uri != null) && (isMshStringValid(uri))) {
                    confirmServerSetup(uri)
                }
            }
        }
    }

    fun isMshStringValid(x:String):Boolean {
        if (x.startsWith("mc://") == false)  return false
        var xs = x.split(',')
        if (xs.count() < 3) return false
        if (xs[0].length < 8) return false
        if (xs[1].length < 3) return false
        if (xs[2].length < 3) return false
        if (xs[0].indexOf('.') == -1) return false
        return true
    }

    fun moveToScanner() {
        findNavController().navigate(R.id.action_FirstFragment_to_SecondFragment)
    }

    fun moveToWebPage(pageUrl: String) {
        findNavController().navigate(R.id.action_FirstFragment_to_webViewFragment)
    }

    fun refreshInfo() {
        view?.findViewById<TextView>(R.id.serverNameTextView)?.text = getServerHost(serverLink)
        if (serverLink == null) {
            // Server not setup
            view?.findViewById<ImageView>(R.id.mainImageView)?.alpha = 0.4F
            view?.findViewById<TextView>(R.id.agentStatusTextview)?.text = getString(R.string.no_server_setup)
            view?.findViewById<TextView>(R.id.agentActionButton)?.text = getString(R.string.setup_server)
            view?.findViewById<TextView>(R.id.agentActionButton)?.isEnabled = cameraPresent
        } else {
            // Server is setup, display state of the agent
            var state : Int = 0;
            if (meshAgent != null) { state = meshAgent!!.state; }
            view?.findViewById<TextView>(R.id.agentActionButton)?.isEnabled = true
            if ((state == 0) || (state == null)) {
                // Disconnected
                view?.findViewById<ImageView>(R.id.mainImageView)?.alpha = 0.5F
                view?.findViewById<TextView>(R.id.agentStatusTextview)?.text =
                    getString(R.string.disconnected)
                view?.findViewById<TextView>(R.id.agentActionButton)?.text =
                    getString(R.string.connect)
            } else if (state == 1) {
                // Connecting
                view?.findViewById<ImageView>(R.id.mainImageView)?.alpha = 0.5F
                view?.findViewById<TextView>(R.id.agentStatusTextview)?.text =
                    getString(R.string.connecting)
                view?.findViewById<TextView>(R.id.agentActionButton)?.text =
                    getString(R.string.disconnect)
            } else if (state == 2) {
                // Verifying
                view?.findViewById<ImageView>(R.id.mainImageView)?.alpha = 0.5F
                view?.findViewById<TextView>(R.id.agentStatusTextview)?.text =
                    getString(R.string.authenticating)
                view?.findViewById<TextView>(R.id.agentActionButton)?.text =
                    getString(R.string.disconnect)
            } else if (state == 3) {
                // Connected
                view?.findViewById<ImageView>(R.id.mainImageView)?.alpha = 1.0F
                view?.findViewById<TextView>(R.id.agentStatusTextview)?.text =
                        getString(R.string.connected)
                view?.findViewById<TextView>(R.id.agentActionButton)?.text =
                        getString(R.string.disconnect)
            }
        }
    }

    fun getServerHost(serverLink : String?) : String? {
        if (serverLink == null) return null
        var x : List<String> = serverLink.split(',')
        var serverHost = x[0]
        serverHost = serverHost.substring(5) // Remove the mc://
        var i = serverHost.indexOf(':')
        if (i > 0) { serverHost = serverHost.substring(0, i) } // Remove the port number if present
        return serverHost
    }

    override fun onPermissionsChecked(report: MultiplePermissionsReport?) {
        println("onPermissionsChecked")
        (activity as MainActivity).toggleAgentConnection()
    }

    override fun onPermissionRationaleShouldBeShown(permissions: MutableList<PermissionRequest>?, token: PermissionToken?) {
        println("onPermissionRationaleShouldBeShown")
        token?.continuePermissionRequest()
    }

    fun confirmServerSetup(x:String) {
        val builder = AlertDialog.Builder(activity)
        builder.setTitle("MeshCentral Server")
        builder.setMessage("Setup to: ${getServerHost(x)}?")
        builder.setPositiveButton(android.R.string.ok) { _, _ ->
            (activity as MainActivity).setMeshServerLink(x)
            (activity as MainActivity).intent.removeExtra("key");
            (activity as MainActivity).intent.action = "";
            (activity as MainActivity).intent.data = null;
        }
        builder.setNeutralButton(android.R.string.cancel) { _, _ ->
            (activity as MainActivity).intent.removeExtra("key");
            (activity as MainActivity).intent.action = "";
            (activity as MainActivity).intent.data = null;
        }
        alert = builder.show()
    }

    override fun onDestroy() {
        if (alert != null) {
            alert?.dismiss()
            alert = null
        }
        super.onDestroy()
    }
}