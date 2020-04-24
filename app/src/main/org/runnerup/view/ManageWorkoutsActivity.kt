/*
 * Copyright (C) 2013 jonas.oreland@gmail.com
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.runnerup.view

import android.app.Activity
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.provider.MediaStore
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.runnerup.R
import org.runnerup.common.util.Constants
import org.runnerup.common.util.Constants.DB
import org.runnerup.content.WorkoutFileProvider
import org.runnerup.db.DBHelper
import org.runnerup.export.SyncManager
import org.runnerup.export.SyncManager.WorkoutRef
import org.runnerup.export.Synchronizer
import org.runnerup.view.ManageWorkoutsActivity
import org.runnerup.workout.WorkoutSerializer
import java.io.*
import java.util.*

class ManageWorkoutsActivity : AppCompatActivity(), Constants {
    private var mDB: SQLiteDatabase? = null
    private var PHONE_STRING: String? = null
    private val pendingWorkouts = HashSet<WorkoutRef>()
    private val providers = ArrayList<ContentValues>()
    private val workouts = HashMap<String?, ArrayList<WorkoutRef>>()
    private var adapter: WorkoutAccountListAdapter? = null
    private val loadedProviders = HashSet<String>()
    private var uploading = false
    private var currentlySelectedWorkout: CompoundButton? = null
    private var downloadButton: Button? = null
    private var deleteButton: Button? = null
    private var shareButton: Button? = null
    private var createButton: Button? = null
    private var syncManager: SyncManager? = null

    /**
     * Called when the activity is first created.
     */
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.manage_workouts)
        PHONE_STRING = resources.getString(R.string.my_phone)
        mDB = DBHelper.getReadableDatabase(this)
        syncManager = SyncManager(this)
        adapter = WorkoutAccountListAdapter(this)
        val list = findViewById<View>(R.id.expandable_list_view) as ExpandableListView
        list.setAdapter(adapter)
        downloadButton = findViewById<View>(R.id.download_workout_button) as Button
        downloadButton!!.setOnClickListener(downloadButtonClick)
        // No download provider currently exists
        downloadButton!!.visibility = View.GONE
        deleteButton = findViewById<View>(R.id.delete_workout_button) as Button
        deleteButton!!.setOnClickListener(deleteButtonClick)
        createButton = findViewById<View>(R.id.create_workout_button) as Button
        createButton!!.setOnClickListener(createButtonClick)
        shareButton = findViewById<View>(R.id.share_workout_button) as Button
        shareButton!!.setOnClickListener(shareButtonClick)
        handleButtons()
        requery()
        listLocal()
        list.expandGroup(0)
        val data = intent.data
        if (data != null) {
            intent.data = null
            var fileName = getFilename(data)
            if (fileName == null) fileName = "noname"
            try {
                importData(fileName, data)
            } catch (e: Exception) {
                val builder = AlertDialog.Builder(this)
                        .setTitle(getString(R.string.Problem))
                        .setMessage(getString(R.string.Failed_to_import) + ": " + fileName)
                        .setPositiveButton(getString(R.string.OK_darn)
                        ) { dialog, which ->
                            dialog.dismiss()
                            finish()
                        }
                builder.show()
            }
        }
        // launch home Activity (with FLAG_ACTIVITY_CLEAR_TOP)
    }

    private fun getFilename(data: Uri): String? {
        Log.i(javaClass.name, "scheme: $data")
        var name: String? = null
        if (ContentResolver.SCHEME_FILE.contentEquals(data.scheme)) {
            name = data.lastPathSegment
        } else if (ContentResolver.SCHEME_CONTENT.contentEquals(data.scheme)) {
            val projection = arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME
            )
            val c = contentResolver.query(data, projection, null, null, null)
            if (c != null) {
                c.moveToFirst()
                val fileNameColumnId = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                if (fileNameColumnId >= 0) name = c.getString(fileNameColumnId)
                c.close()
            }
        }
        return name
    }

    @Throws(Exception::class)
    private fun importData(fileName: String, data: Uri) {
        val cr = contentResolver
        val `is` = cr.openInputStream(data) ?: throw Exception("Failed to get input stream")
        val w = WorkoutSerializer.readJSON(BufferedReader(InputStreamReader(`is`)))
        `is`.close()
        if (w == null) throw Exception("Failed to parse content")
        val exists = WorkoutSerializer.getFile(this, fileName).exists()
        val selected = booleanArrayOf(
                false
        )
        val builder = AlertDialog.Builder(this)
                .setTitle(getString(R.string.Import_workout) + ": " + fileName)
                .setPositiveButton(getString(R.string.Yes)
                ) { dialog, which ->
                    dialog.dismiss()
                    var saveName = fileName
                    try {
                        if (exists && !selected[0]) {
                            var name = ""
                            val tmp = fileName.split("\\.").toTypedArray()
                            if (tmp.size > 0) {
                                for (i in 0 until tmp.size - 1) name = name + tmp[i]
                            } else {
                                name = fileName
                            }
                            val ending = if (tmp.size > 0) "." + tmp[tmp.size - 1] else ""
                            var newName = fileName
                            for (i in 1..24) {
                                newName = "$name-$i$ending"
                                if (!WorkoutSerializer.getFile(this@ManageWorkoutsActivity,
                                                newName).exists()) break
                            }
                            saveName = newName
                            Toast.makeText(this@ManageWorkoutsActivity,
                                    getString(R.string.Saving_as) + " " + saveName, Toast.LENGTH_SHORT).show()
                        }
                        saveImport(saveName, cr.openInputStream(data))
                    } catch (e: FileNotFoundException) {
                        e.printStackTrace()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                    launchMain(saveName)
                }
                .setNegativeButton(getString(R.string.No_way)
                ) { dialog, which -> // Do nothing but close the dialog
                    dialog.dismiss()
                    finish()
                }
        if (exists) {
            val items = arrayOf(
                    getString(R.string.Overwrite_existing)
            )
            builder.setMultiChoiceItems(items, selected
            ) { arg0, arg1, arg2 -> selected[arg1] = arg2 }
        }
        builder.show()
    }

    @Throws(IOException::class)
    private fun saveImport(file: String, `is`: InputStream) {
        val f = WorkoutSerializer.getFile(this, file)
        val out = BufferedOutputStream(FileOutputStream(f))
        val `in` = BufferedInputStream(`is`)
        val buf = ByteArray(1024)
        while (`in`.read(buf) > 0) {
            out.write(buf)
        }
        `in`.close()
        out.close()
    }

    private fun launchMain(fileName: String) {
        val pref = PreferenceManager.getDefaultSharedPreferences(this)
        pref.edit().putString(resources.getString(R.string.pref_advanced_workout), fileName).apply()
        val intent = Intent(this, MainLayout::class.java)
                .putExtra("mode", StartActivity.TAB_ADVANCED)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
        finish()
    }

    override fun onResume() {
        super.onResume()
        listLocal()
    }

    private fun handleButtons() {
        if (currentlySelectedWorkout == null) {
            downloadButton!!.isEnabled = false
            deleteButton!!.isEnabled = false
            shareButton!!.isEnabled = false
            createButton!!.isEnabled = true
            return
        }
        val selected = currentlySelectedWorkout!!.tag as WorkoutRef
        if (PHONE_STRING!!.contentEquals(selected.synchronizer)) {
            downloadButton!!.isEnabled = false
            deleteButton!!.isEnabled = true
            shareButton!!.isEnabled = true
        } else {
            downloadButton!!.isEnabled = true
            deleteButton!!.isEnabled = false
            shareButton!!.isEnabled = false
        }
    }

    private fun listLocal() {
        val newlist = ArrayList<WorkoutRef>()
        val list = WorkoutListAdapter.load(this)
        if (list != null) {
            for (s in list) {
                newlist.add(WorkoutRef(PHONE_STRING, null,
                        s.substring(0, s.lastIndexOf('.')))
                )
            }
        }
        workouts.remove(PHONE_STRING)
        workouts[PHONE_STRING] = newlist
        adapter!!.notifyDataSetChanged()
    }

    public override fun onDestroy() {
        super.onDestroy()
        DBHelper.closeDB(mDB)
        syncManager!!.close()
    }

    private fun requery() {
        var allSynchronizers: Array<ContentValues>
        run {

            /*
             * Accounts/reports
             */
            val sql = ("SELECT DISTINCT "
                    + "  acc._id, " // 0
                    + ("  acc." + DB.ACCOUNT.NAME + ", ")
                    + ("  acc." + DB.ACCOUNT.AUTH_CONFIG + ", ")
                    + ("  acc." + DB.ACCOUNT.FLAGS + ", ")
                    + ("  acc." + DB.ACCOUNT.ENABLED + " ")
                    + (" FROM " + DB.ACCOUNT.TABLE + " acc "))
            val c = mDB!!.rawQuery(sql, null)
            allSynchronizers = DBHelper.toArray(c)
            c.close()
        }
        providers.clear()
        val phone = ContentValues()
        phone.put(DB.ACCOUNT.NAME, PHONE_STRING)
        providers.add(phone)
        for (tmp in allSynchronizers) {
            val synchronizer = syncManager!!.add(tmp)
            //There is no option to show disabled providers, so check for enable or configured
            if (synchronizer != null && synchronizer.checkSupport(Synchronizer.Feature.WORKOUT_LIST) &&
                    (synchronizer.isConfigured || tmp.getAsInteger(DB.ACCOUNT.ENABLED) == 1)) {
                providers.add(tmp)
                workouts.remove(synchronizer.name)
                workouts[synchronizer.name] = ArrayList()
            }
        }
        adapter!!.notifyDataSetChanged()
    }

    override fun onBackPressed() {
        if (uploading) {
            /*
             * Ignore while uploading
             */
            return
        }
        super.onBackPressed()
    }

    interface Filter<T> {
        fun match(t: T): Boolean
    }

    fun filter(list: List<WorkoutRef>,
               f: Filter<WorkoutRef>): ArrayList<WorkoutRef> {
        val newlist = ArrayList<WorkoutRef>()
        return filter(list, newlist, f)
    }

    private fun filter(list: List<WorkoutRef>,
                       newlist: ArrayList<WorkoutRef>, f: Filter<WorkoutRef>): ArrayList<WorkoutRef> {
        for (w in list) {
            if (f.match(w)) newlist.add(w)
        }
        return newlist
    }

    private val createButtonClick = View.OnClickListener {
        val intent = Intent(this@ManageWorkoutsActivity, CreateAdvancedWorkout::class.java)
        // Set an EditText view to get user input
        val input = EditText(this@ManageWorkoutsActivity)
        val builder = AlertDialog.Builder(this@ManageWorkoutsActivity)
                .setTitle(getString(R.string.Create_new_workout))
                .setMessage(getString(R.string.Set_workout_name))
                .setView(input)
                .setPositiveButton(getString(R.string.OK)) { dialog, whichButton ->
                    val value = input.text.toString()
                    intent.putExtra(WORKOUT_NAME, value)
                    startActivity(intent)
                }
                .setNegativeButton(getString(R.string.Cancel)) { dialog, whichButton -> dialog.dismiss() }
        builder.show()
    }
    private val downloadButtonClick: View.OnClickListener = object : View.OnClickListener {
        override fun onClick(v: View) {
            if (currentlySelectedWorkout == null) return
            val selected = currentlySelectedWorkout!!.tag as WorkoutRef
            val local = workouts[PHONE_STRING]!!
            if (contains(local, selected)) {
                val builder = AlertDialog.Builder(this@ManageWorkoutsActivity)
                        .setTitle(getString(R.string.Downloading_1s_will_overwrite_2_workout_with_same_name, selected.workoutName, PHONE_STRING))
                        .setMessage(getString(R.string.Are_you_sure))
                        .setPositiveButton(getString(R.string.Yes)
                        ) { dialog, which ->
                            dialog.dismiss()
                            downloadWorkout(selected)
                        }
                        .setNegativeButton(getString(R.string.No)
                        ) { dialog, which -> // Do nothing but close the dialog
                            dialog.dismiss()
                        }
                builder.show()
                return
            }
            downloadWorkout(selected)
        }

        private fun downloadWorkout(selected: WorkoutRef) {
            uploading = true
            val list = HashSet<WorkoutRef>()
            list.add(currentlySelectedWorkout!!.tag as WorkoutRef)
            syncManager!!.loadWorkouts(list) { synchronizerName, status ->
                uploading = false
                currentlySelectedWorkout = null
                listLocal()
                handleButtons()
            }
        }

        private fun contains(local: ArrayList<WorkoutRef>,
                             selected: WorkoutRef): Boolean {
            for (w in local) {
                if (selected.workoutName.contentEquals(w.workoutName)) {
                    return true
                }
            }
            return false
        }
    }
    private val deleteButtonClick = View.OnClickListener {
        if (currentlySelectedWorkout == null) return@OnClickListener
        val selected = currentlySelectedWorkout!!.tag as WorkoutRef
        val builder = AlertDialog.Builder(this@ManageWorkoutsActivity)
                .setTitle(getString(R.string.Delete_workout) + " " + selected.workoutName)
                .setMessage(getString(R.string.Are_you_sure))
                .setPositiveButton(getString(R.string.Yes)
                ) { dialog, which ->
                    dialog.dismiss()
                    deleteWorkout(selected)
                }
                .setNegativeButton(getString(R.string.No)
                ) { dialog, which -> // Do nothing but close the dialog
                    dialog.dismiss()
                }
        builder.show()
    }

    private fun deleteWorkout(selected: WorkoutRef) {
        val f = WorkoutSerializer.getFile(this, selected.workoutName)
        f.delete()
        val pref = PreferenceManager.getDefaultSharedPreferences(this)
        if (selected.workoutName.contentEquals(pref.getString(resources.getString(R.string.pref_advanced_workout), ""))) {
            pref.edit().putString(resources.getString(R.string.pref_advanced_workout), "").apply()
        }
        currentlySelectedWorkout = null
        listLocal()
    }

    private val onWorkoutChecked = CompoundButton.OnCheckedChangeListener { arg0, isChecked ->
        if (currentlySelectedWorkout != null) {
            currentlySelectedWorkout!!.isChecked = false
        }
        currentlySelectedWorkout = if (isChecked) {
            arg0
        } else {
            null
        }
        handleButtons()
    }
    var loadWorkoutButtonClick = View.OnClickListener {
        uploading = true
        syncManager!!.loadWorkouts(pendingWorkouts) { synchronizerName, status ->
            uploading = false
            listLocal()
        }
    }
    private val shareButtonClick = View.OnClickListener {
        if (currentlySelectedWorkout == null) return@OnClickListener
        val context: Activity = this@ManageWorkoutsActivity
        val selected = currentlySelectedWorkout!!.tag as WorkoutRef
        val name = selected.workoutName
        val intent = Intent(Intent.ACTION_SEND)
        intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.RunnerUp_workout) + ": " + name)
        intent.putExtra(Intent.EXTRA_TEXT, getString(R.string.HinHere_is_a_workout_I_think_you_might_like))
        intent.type = WorkoutFileProvider.MIME
        val uri = Uri.parse("content://" + WorkoutFileProvider.AUTHORITY + "/" + name + ".json")
        intent.putExtra(Intent.EXTRA_STREAM, uri)
        context.startActivity(Intent.createChooser(intent, getString(R.string.Share_workout)))
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == SyncManager.CONFIGURE_REQUEST) {
            syncManager!!.onActivityResult(requestCode, resultCode, data)
        }
        requery()
    }

    internal inner class WorkoutAccountListAdapter(val context: Context) : BaseExpandableListAdapter() {
        fun getProvider(index: Int): String {
            return providers[index].getAsString(DB.ACCOUNT.NAME)
        }

        override fun getChild(groupPosition: Int, childPosition: Int): Any {
            return workouts[getProvider(groupPosition)]!![childPosition]
        }

        override fun getChildId(groupPosition: Int, childPosition: Int): Long {
            return 0
        }

        override fun getChildView(groupPosition: Int, childPosition: Int,
                                  isLastChild: Boolean, view: View, parent: ViewGroup): View {
            var view = view
            if (view == null || view !is LinearLayout) {
                val infalInflater = context
                        .getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
                view = infalInflater.inflate(R.layout.manage_workouts_list_row, parent, false)
            }
            val workout = workouts[getProvider(groupPosition)]!![childPosition]
            val cb = view.findViewById<View>(R.id.download_workout_checkbox) as RadioButton
            cb.tag = workout
            cb.isChecked = (currentlySelectedWorkout != null
                    && currentlySelectedWorkout!!.tag === workout)
            cb.setOnCheckedChangeListener(onWorkoutChecked)
            cb.text = workout.workoutName
            return view
        }

        override fun getChildrenCount(groupPosition: Int): Int {
            return workouts[getProvider(groupPosition)]!!.size
        }

        override fun getGroup(groupPosition: Int): Any {
            return providers[groupPosition]
        }

        override fun getGroupCount(): Int {
            return providers.size
        }

        override fun getGroupId(groupPosition: Int): Long {
            return 0
        }

        override fun getGroupView(groupPosition: Int, isExpanded: Boolean,
                                  convertView: View, parent: ViewGroup): View {
            var convertView = convertView
            if (convertView == null) {
                val inflater = context
                        .getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
                convertView = inflater.inflate(R.layout.manage_workouts_list_category, parent, false)
            }
            val categoryText = convertView.findViewById<View>(R.id.category_text) as TextView
            categoryText.text = getProvider(groupPosition)
            if (isExpanded) categoryText.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_expand_up_white_24dp, 0) else categoryText.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_expand_down_white_24dp, 0)
            return convertView
        }

        override fun hasStableIds(): Boolean {
            return false
        }

        override fun isChildSelectable(groupPosition: Int, childPosition: Int): Boolean {
            return false
        }

        var saveGroupPosition = 0
        override fun onGroupExpanded(groupPosition: Int) {
            val provider = getProvider(groupPosition)
            if (PHONE_STRING!!.contentEquals(provider)) {
                super.onGroupExpanded(groupPosition)
                return
            }
            if (loadedProviders.contains(provider)) {
                super.onGroupExpanded(groupPosition)
                return
            }
            uploading = true
            saveGroupPosition = groupPosition
            if (!syncManager!!.isConfigured(provider)) {
                syncManager!!.connect(onSynchronizerConfiguredCallback, provider)
            } else {
                onSynchronizerConfiguredCallback.run(provider, Synchronizer.Status.OK)
            }
        }

        val onSynchronizerConfiguredCallback: SyncManager.Callback = object : SyncManager.Callback {
            override fun run(synchronizerName: String, status: Synchronizer.Status) {
                Log.i(javaClass.name, "status: $status")
                if (status != Synchronizer.Status.OK) {
                    uploading = false
                    return
                }
                val list = workouts[synchronizerName]!!
                list.clear()
                val tmp = HashSet<String>()
                tmp.add(synchronizerName)
                syncManager!!.loadWorkoutList(list, onLoadWorkoutListCallback, tmp)
            }
        }

        private fun onGroupExpandedImpl() {
            super.onGroupExpanded(saveGroupPosition)
        }

        private val onLoadWorkoutListCallback = SyncManager.Callback { synchronizerName, status ->
            uploading = false
            if (status == Synchronizer.Status.OK) {
                loadedProviders.add(getProvider(saveGroupPosition))
                adapter!!.notifyDataSetChanged()
                onGroupExpandedImpl()
            }
        }

        override fun onGroupCollapsed(groupPosition: Int) {
            super.onGroupCollapsed(groupPosition)
            val provider = getProvider(groupPosition)
            if (currentlySelectedWorkout != null) {
                val ref = currentlySelectedWorkout!!.tag as WorkoutRef
                if (ref.synchronizer.contentEquals(provider)) {
                    currentlySelectedWorkout!!.isChecked = false
                    currentlySelectedWorkout = null
                }
            }
        }

    }

    companion object {
        const val WORKOUT_NAME = ""
    }
}