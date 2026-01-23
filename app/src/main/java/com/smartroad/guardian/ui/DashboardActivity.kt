package com.smartroad.guardian.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.smartroad.guardian.R
import com.smartroad.guardian.databinding.ActivityDashboardBinding
import com.smartroad.guardian.logic.ViolationEngine
import com.smartroad.guardian.storage.ViolationEntity
import com.smartroad.guardian.storage.ViolationRepository
import com.smartroad.guardian.workers.EmailWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Dashboard Activity - Violation History
 * 
 * Features per PRD:
 * - List of all violations with thumbnails
 * - Filter by type  
 * - View full-size evidence
 * - Export CSV
 * - Send email reports
 * - Batch delete
 * - Statistics summary
 */
class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private lateinit var repository: ViolationRepository
    private lateinit var adapter: ViolationAdapter
    
    private var currentFilter: String? = null
    private var violations: List<ViolationEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        repository = ViolationRepository(this)
        
        setupToolbar()
        setupRecyclerView()
        setupFilters()
        setupButtons()
        loadStats()
        loadViolations()
    }
    
    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }
    
    private fun setupRecyclerView() {
        adapter = ViolationAdapter(
            onItemClick = { violation -> showViolationDetail(violation) },
            onDeleteClick = { violation -> confirmDelete(violation) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }
    
    private fun setupFilters() {
        binding.chipAll.setOnClickListener { setFilter(null) }
        binding.chipHelmet.setOnClickListener { setFilter(ViolationEngine.ViolationType.NO_HELMET.name) }
        binding.chipTriple.setOnClickListener { setFilter(ViolationEngine.ViolationType.TRIPLE_RIDING.name) }
        binding.chipPlate.setOnClickListener { setFilter(ViolationEngine.ViolationType.NO_PLATE.name) }
    }
    
    private fun setFilter(type: String?) {
        currentFilter = type
        
        // Update chip states
        binding.chipAll.isChecked = type == null
        binding.chipHelmet.isChecked = type == ViolationEngine.ViolationType.NO_HELMET.name
        binding.chipTriple.isChecked = type == ViolationEngine.ViolationType.TRIPLE_RIDING.name
        binding.chipPlate.isChecked = type == ViolationEngine.ViolationType.NO_PLATE.name
        
        applyFilter()
    }
    
    private fun applyFilter() {
        val filtered = if (currentFilter == null) {
            violations
        } else {
            violations.filter { it.type == currentFilter }
        }
        
        adapter.submitList(filtered)
        binding.emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }
    
    private fun setupButtons() {
        binding.btnExport.setOnClickListener { exportData() }
        binding.btnEmail.setOnClickListener { sendEmailReport() }
        binding.btnClearAll.setOnClickListener { confirmClearAll() }
    }
    
    private fun loadStats() {
        lifecycleScope.launch {
            // Total violations
            val total = repository.getTotalCount().first()
            binding.statTotal.text = total.toString()
            
            // Today's count
            val today = repository.getTodayCount().first()
            binding.statToday.text = today.toString()
            
            // Pending sync
            val pending = repository.getUnsyncedCount().first()
            binding.statPending.text = pending.toString()
            
            // By type stats
            repository.getViolationStats().collect { stats ->
                binding.statHelmet.text = stats.find { it.type == ViolationEngine.ViolationType.NO_HELMET.name }?.count?.toString() ?: "0"
                binding.statTriple.text = stats.find { it.type == ViolationEngine.ViolationType.TRIPLE_RIDING.name }?.count?.toString() ?: "0"
                binding.statPlate.text = stats.find { it.type == ViolationEngine.ViolationType.NO_PLATE.name }?.count?.toString() ?: "0"
            }
        }
    }
    
    private fun loadViolations() {
        lifecycleScope.launch {
            repository.getAllViolations().collect { list ->
                violations = list
                applyFilter()
            }
        }
    }
    
    private fun showViolationDetail(violation: ViolationEntity) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_violation_detail, null)
        
        // Load full image
        val imageView = dialogView.findViewById<ImageView>(R.id.fullImage)
        Glide.with(this)
            .load(File(violation.imagePath))
            .into(imageView)
        
        // Set details
        dialogView.findViewById<TextView>(R.id.detailType).text = getDisplayName(violation.type)
        dialogView.findViewById<TextView>(R.id.detailConfidence).text = 
            String.format("%.1f%%", violation.confidence * 100)
        dialogView.findViewById<TextView>(R.id.detailTimestamp).text = 
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(violation.timestamp))
        
        val location = if (violation.latitude != 0.0 || violation.longitude != 0.0) {
            String.format("%.6f, %.6f", violation.latitude, violation.longitude)
        } else {
            "Not available"
        }
        dialogView.findViewById<TextView>(R.id.detailLocation).text = location
        
        AlertDialog.Builder(this, R.style.Theme_SmartRoad_Dialog)
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .setNeutralButton("Share") { _, _ ->
                shareViolation(violation)
            }
            .setNegativeButton("Delete") { _, _ ->
                confirmDelete(violation)
            }
            .show()
    }
    
    private fun getDisplayName(typeName: String): String {
        return when (typeName) {
            ViolationEngine.ViolationType.NO_HELMET.name -> "No Helmet"
            ViolationEngine.ViolationType.TRIPLE_RIDING.name -> "Triple Riding"
            ViolationEngine.ViolationType.NO_PLATE.name -> "No Plate"
            else -> typeName
        }
    }
    
    private fun shareViolation(violation: ViolationEntity) {
        try {
            val file = File(violation.imagePath)
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.provider",
                file
            )
            
            val dateTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(Date(violation.timestamp))
            
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "SmartRoad Violation: ${getDisplayName(violation.type)}")
                putExtra(Intent.EXTRA_TEXT, 
                    "Violation: ${getDisplayName(violation.type)}\n" +
                    "Time: $dateTime\n" +
                    "Confidence: ${String.format("%.1f%%", violation.confidence * 100)}\n" +
                    "Location: ${violation.latitude}, ${violation.longitude}"
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            startActivity(Intent.createChooser(intent, "Share Violation"))
        } catch (e: Exception) {
            Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun confirmDelete(violation: ViolationEntity) {
        AlertDialog.Builder(this)
            .setTitle("Delete Violation")
            .setMessage("Are you sure you want to delete this violation?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    repository.deleteViolation(violation.id)
                    loadStats()
                    Toast.makeText(this@DashboardActivity, "Deleted", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle("Clear All Violations")
            .setMessage("This will permanently delete all ${violations.size} violations. This cannot be undone.")
            .setPositiveButton("Clear All") { _, _ ->
                lifecycleScope.launch {
                    repository.deleteAll()
                    loadStats()
                    Toast.makeText(this@DashboardActivity, "All violations cleared", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun exportData() {
        lifecycleScope.launch {
            try {
                val file = repository.exportToCsv()
                AlertDialog.Builder(this@DashboardActivity)
                    .setTitle("Export Complete")
                    .setMessage("${violations.size} violations exported to:\n${file.name}")
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Share") { _, _ ->
                        shareFile(file)
                    }
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this@DashboardActivity, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share CSV"))
    }
    
    private fun sendEmailReport() {
        if (violations.isEmpty()) {
            Toast.makeText(this, "No violations to report", Toast.LENGTH_SHORT).show()
            return
        }
        
        EmailWorker.scheduleReport(this)
        Toast.makeText(this, "Email report scheduled", Toast.LENGTH_SHORT).show()
    }
}

/**
 * RecyclerView Adapter for Violations
 */
class ViolationAdapter(
    private val onItemClick: (ViolationEntity) -> Unit,
    private val onDeleteClick: (ViolationEntity) -> Unit
) : RecyclerView.Adapter<ViolationAdapter.ViewHolder>() {

    private var items: List<ViolationEntity> = emptyList()
    private val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())

    fun submitList(list: List<ViolationEntity>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_violation, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.violationThumbnail)
        private val type: TextView = itemView.findViewById(R.id.violationType)
        private val time: TextView = itemView.findViewById(R.id.violationTime)
        private val confidence: TextView = itemView.findViewById(R.id.violationConfidence)
        private val syncStatus: View = itemView.findViewById(R.id.syncStatus)
        
        fun bind(violation: ViolationEntity) {
            // Load thumbnail
            val thumbPath = violation.thumbnailPath ?: violation.imagePath
            Glide.with(itemView.context)
                .load(File(thumbPath))
                .placeholder(R.drawable.ic_camera)
                .into(thumbnail)
            
            // Set type with color
            type.text = getDisplayName(violation.type)
            type.setTextColor(getColorForType(violation.type))
            
            // Time
            time.text = dateFormat.format(Date(violation.timestamp))
            
            // Confidence
            confidence.text = String.format("%.0f%%", violation.confidence * 100)
            
            // Sync status
            syncStatus.setBackgroundResource(
                if (violation.isSynced) R.drawable.circle_synced 
                else R.drawable.circle_pending
            )
            
            // Click listeners
            itemView.setOnClickListener { onItemClick(violation) }
            itemView.setOnLongClickListener { 
                onDeleteClick(violation)
                true 
            }
        }
        
        private fun getDisplayName(typeName: String): String {
            return when (typeName) {
                ViolationEngine.ViolationType.NO_HELMET.name -> "No Helmet"
                ViolationEngine.ViolationType.TRIPLE_RIDING.name -> "Triple Riding"
                ViolationEngine.ViolationType.NO_PLATE.name -> "No Plate"
                else -> typeName
            }
        }
        
        private fun getColorForType(typeName: String): Int {
            return when (typeName) {
                ViolationEngine.ViolationType.NO_HELMET.name -> android.graphics.Color.parseColor("#FF5722")
                ViolationEngine.ViolationType.TRIPLE_RIDING.name -> android.graphics.Color.parseColor("#E91E63")
                ViolationEngine.ViolationType.NO_PLATE.name -> android.graphics.Color.parseColor("#9C27B0")
                else -> android.graphics.Color.WHITE
            }
        }
    }
}
