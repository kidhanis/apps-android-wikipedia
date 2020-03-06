package org.wikipedia.suggestededits

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.dialog_image_tag_select.*
import org.wikipedia.R
import org.wikipedia.WikipediaApp
import org.wikipedia.activity.FragmentUtil
import org.wikipedia.dataclient.Service
import org.wikipedia.dataclient.ServiceFactory
import org.wikipedia.dataclient.WikiSite
import org.wikipedia.dataclient.mwapi.MwQueryPage
import org.wikipedia.dataclient.wikidata.Search
import org.wikipedia.util.log.L
import java.util.*
import kotlin.collections.ArrayList

class SuggestedEditsImageTagDialog : DialogFragment() {
    interface Callback {
        fun onSelect(item: MwQueryPage.ImageLabel)
    }

    private var currentSearchTerm: String = ""
    private val textWatcher = SearchTextWatcher()
    private val adapter = ResultListAdapter(Collections.emptyList())
    private val disposables = CompositeDisposable()

    private val searchRunnable = Runnable {
        requestResults(currentSearchTerm)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return LayoutInflater.from(requireActivity()).inflate(R.layout.dialog_image_tag_select, container)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        imageTagsRecycler.layoutManager = LinearLayoutManager(activity)
        imageTagsRecycler.adapter = adapter
        imageTagsSearchText.addTextChangedListener(textWatcher)
        applyResults(Collections.emptyList())
    }

    override fun onStart() {
        super.onStart()
        try {
            if (requireArguments().getBoolean("useClipboardText")) {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                if (clipboard.hasPrimaryClip() && clipboard.primaryClip != null) {
                    val primaryClip = clipboard.primaryClip!!
                    val clipText = primaryClip.getItemAt(primaryClip.itemCount - 1).coerceToText(requireContext()).toString()
                    if (clipText.isNotEmpty()) {
                        imageTagsSearchText.setText(clipText)
                        imageTagsSearchText.selectAll()
                    }
                }
            }
        } catch (ignore: Exception) {
        }
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        imageTagsSearchText.removeTextChangedListener(textWatcher)
        disposables.clear()
    }

    private inner class SearchTextWatcher : TextWatcher {
        override fun beforeTextChanged(text: CharSequence, i: Int, i1: Int, i2: Int) { }

        override fun onTextChanged(text: CharSequence, i: Int, i1: Int, i2: Int) {
            currentSearchTerm = text.toString()
            imageTagsSearchText.removeCallbacks(searchRunnable)
            imageTagsSearchText.postDelayed(searchRunnable, 500)
        }

        override fun afterTextChanged(editable: Editable) { }
    }

    private fun requestResults(searchTerm: String) {
        if (searchTerm.isEmpty()) {
            applyResults(Collections.emptyList())
            return
        }
        disposables.add(ServiceFactory.get(WikiSite(Service.WIKIDATA_URL)).searchEntities(searchTerm, WikipediaApp.getInstance().appOrSystemLanguageCode, WikipediaApp.getInstance().appOrSystemLanguageCode)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ search: Search ->
                    val labelList = ArrayList<MwQueryPage.ImageLabel>()
                    for (result in search.results()) {
                        val label = MwQueryPage.ImageLabel(result.id, result.label, result.description)
                        labelList.add(label)
                    }
                    applyResults(labelList)
                }) { t: Throwable? ->
                    L.d(t)
                })
    }

    private fun applyResults(results: List<MwQueryPage.ImageLabel>) {
        adapter.setResults(results)
        adapter.notifyDataSetChanged()
        if (currentSearchTerm.isEmpty()) {
            noResultsText.visibility = View.GONE
            imageTagsRecycler.visibility = View.GONE
            imageTagsDivider.visibility = View.INVISIBLE
        } else {
            imageTagsDivider.visibility = View.VISIBLE
            if (results.isEmpty()) {
                noResultsText.visibility = View.VISIBLE
                imageTagsRecycler.visibility = View.GONE
            } else {
                noResultsText.visibility = View.GONE
                imageTagsRecycler.visibility = View.VISIBLE
            }
        }
    }

    private inner class ResultItemHolder internal constructor(itemView: View) : RecyclerView.ViewHolder(itemView), View.OnClickListener {
        fun bindItem(item: MwQueryPage.ImageLabel, position: Int) {
            itemView.findViewById<TextView>(R.id.labelName).text = item.label
            itemView.findViewById<TextView>(R.id.labelDescription).text = item.description
            itemView.tag = item
            itemView.setOnClickListener(this)
        }

        override fun onClick(v: View?) {
            val item = v!!.tag as MwQueryPage.ImageLabel
            callback()!!.onSelect(item)
            dismiss()
        }
    }

    private inner class ResultListAdapter(private var results: List<MwQueryPage.ImageLabel>) : RecyclerView.Adapter<ResultItemHolder>() {
        fun setResults(results: List<MwQueryPage.ImageLabel>) {
            this.results = results
        }

        override fun getItemCount(): Int {
            return results.size
        }

        override fun onCreateViewHolder(parent: ViewGroup, pos: Int): ResultItemHolder {
            val view = layoutInflater.inflate(R.layout.item_wikidata_label, null)
            val params = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            view.layoutParams = params
            return ResultItemHolder(view)
        }

        override fun onBindViewHolder(holder: ResultItemHolder, pos: Int) {
            holder.bindItem(results[pos], pos)
        }
    }

    private fun callback(): Callback? {
        return FragmentUtil.getCallback(this, Callback::class.java)
    }

    companion object {
        fun newInstance(useClipboardText: Boolean): SuggestedEditsImageTagDialog {
            val dialog = SuggestedEditsImageTagDialog()
            val args = Bundle()
            args.putBoolean("useClipboardText", useClipboardText)
            dialog.arguments = args
            return dialog
        }
    }
}
