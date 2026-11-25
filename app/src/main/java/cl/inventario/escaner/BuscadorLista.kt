package cl.inventario.escaner

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Filter
import java.text.Normalizer

/**
 * Lista para un campo con sugerencias que filtra mientras se escribe:
 * busca el texto en cualquier parte del nombre y sin importar tildes ni mayúsculas
 * («bio» → Biobío, «nuble» → Ñuble, «santiago» → Metropolitana de Santiago).
 */
class BuscadorLista(context: Context, private val todos: List<String>) :
    ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, ArrayList(todos)) {

    private val filtro = object : Filter() {
        override fun performFiltering(texto: CharSequence?): FilterResults {
            val q = simple(texto?.toString().orEmpty())
            val lista = if (q.isEmpty()) todos else {
                val empiezan = todos.filter { r -> simple(r).split(' ').any { it.startsWith(q) } || simple(r).startsWith(q) }
                val contienen = todos.filter { r -> simple(r).contains(q) && r !in empiezan }
                empiezan + contienen
            }
            return FilterResults().apply { values = lista; count = lista.size }
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(texto: CharSequence?, r: FilterResults?) {
            clear()
            addAll((r?.values as? List<String>) ?: todos)
            notifyDataSetChanged()
        }

        override fun convertResultToString(valor: Any?) = valor?.toString() ?: ""
    }

    override fun getFilter(): Filter = filtro

    /** Si lo escrito corresponde a una sola opción, devuelve esa opción completa. */
    fun unicaCoincidencia(texto: String): String? {
        val q = simple(texto)
        if (q.isEmpty()) return null
        todos.firstOrNull { simple(it) == q }?.let { return it }
        return todos.filter { simple(it).contains(q) }.singleOrNull()
    }

    companion object {
        fun simple(s: String): String =
            Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
                .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex(" +"), " ").trim()
    }
}
