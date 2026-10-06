package com.example.matrizapp
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import kotlinx.coroutines.launch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FiltroFechaScreen(viewModel: FiltroFechaViewModel, notificacionesHelper: NotificacionesHelper, searchQuery: String = "") {
    val allItems by viewModel.filteredList.collectAsState(); val df=remember{SimpleDateFormat("dd/MM/yyyy",Locale.getDefault())}; var itemToView by remember{mutableStateOf<MatrizEntity?>(null)}; val context=LocalContext.current
    val soloPagados by viewModel.soloPagados.collectAsState(); val totalCobrado by viewModel.totalCobradoRango.collectAsState(); val totalTickets by viewModel.totalTicketsRango.collectAsState()
    var verTickets by remember { mutableStateOf(false) }; val ticketsLista by viewModel.ticketsRango.collectAsState()
    val items=rememberItemsFiltrados(allItems,searchQuery){listOf(it.nombre,it.numTT,it.observaciones,it.estado)}
    if(verTickets){
        val fmtHora=remember{SimpleDateFormat("dd/MM HH:mm",Locale("es","MX"))}
        AlertDialog(onDismissRequest={verTickets=false},
            title={Text("Tickets leídos · $"+"%.2f".format(Locale.US,totalTickets))},
            text={
                if(ticketsLista.isEmpty())Text("No hay tickets leídos en este rango.")
                else LazyColumn(Modifier.heightIn(max=360.dp)){items(ticketsLista,key={it.id}){t->
                    Row(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
                        Column(Modifier.weight(1f)){
                            Text(t.nombre?:t.cu?:"Sin nombre",style=MaterialTheme.typography.bodyMedium)
                            Text(fmtHora.format(Date(t.fecha))+(t.cu?.let{" · CU "+it}?:""),style=MaterialTheme.typography.bodySmall,color=Color.Gray)
                        }
                        Text("$"+"%.2f".format(Locale.US,t.monto),style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Bold)
                        TextButton(onClick={viewModel.borrarTicket(t.id)}){Text("Borrar",color=MaterialTheme.colorScheme.error)}
                    }
                    Divider()
                }}
            },
            confirmButton={TextButton(onClick={verTickets=false}){Text("Cerrar")}})
    }
    Column(Modifier.fillMaxSize()){
        // Resumen fijo: permanece siempre arriba de Filtro Fecha y no forma parte de la lista desplazable.
        Surface(color=MaterialTheme.colorScheme.primaryContainer,modifier=Modifier.fillMaxWidth().clickable{verTickets=true}){
            Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=5.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){Text("TICKETS",style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold);Text("$" + "%.2f".format(Locale.US,totalTickets),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)}
                Divider(modifier=Modifier.height(28.dp).width(1.dp))
                Column(Modifier.weight(1f)){Text("STATUS PAGADO",style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold);Text("$" + "%.2f".format(Locale.US,totalCobrado),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)}
            }
        }
        if(soloPagados)Surface(color=MaterialTheme.colorScheme.primaryContainer,modifier=Modifier.fillMaxWidth()){Text("Cobrado: $${"%.2f".format(Locale.US,totalCobrado)}",modifier=Modifier.padding(12.dp),fontWeight=FontWeight.Bold)}
        if(items.isEmpty())Box(Modifier.fillMaxSize(),Alignment.Center){Text(if(soloPagados)"Sin pagos hoy" else "Sin registros de hoy",color=Color.Gray)}else LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){items(items,key={it.id}){item->FiltroItemCard(item,df,viewModel.driveHelper){itemToView=item}}}}
    itemToView?.let{item->FiltroFechaDetailDialog(item,df,viewModel.driveHelper,onDismiss={itemToView=null},onGuardarEstadoYHora={id,estado,hora->viewModel.guardarEstadoYHora(id,estado,hora,notificacionesHelper){mensaje->if(mensaje!=null)android.widget.Toast.makeText(context,mensaje,android.widget.Toast.LENGTH_LONG).show()}})}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FiltroItemCard(item:MatrizEntity,df:SimpleDateFormat,driveHelper:DriveHelper,onClick:()->Unit){val esRetorno=item.estado.contains("retorno",true);Card(onClick=onClick,modifier=Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=if(esRetorno)Color(0xFFBDBDBD)else MaterialTheme.colorScheme.surface)){Row(Modifier.padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){PortadaThumbnail(item.imagenUrl,driveHelper);Column(Modifier.weight(1f)){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(item.nombre,style=MaterialTheme.typography.titleSmall,modifier=Modifier.weight(1f));StatusBadge(item.estado)};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("TT: ${item.numTT}",style=MaterialTheme.typography.bodySmall);Text("Req: ${item.requisito.ifBlank{"-"}}",style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.Bold)};ColoniaLabel(item.ubicacion);Spacer(Modifier.height(4.dp));ContactActionsRow(numTT=item.numTT,ref1=item.ref1,ref2=item.ref2,ubicacion=item.ubicacion)}}}}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FiltroFechaDetailDialog(item:MatrizEntity,df:SimpleDateFormat,driveHelper:DriveHelper,onDismiss:()->Unit,onGuardarEstadoYHora:(id:String,nuevoEstado:String,nuevaHora:String)->Unit){
    val context=LocalContext.current; val clipboard=androidx.compose.ui.platform.LocalClipboardManager.current; var estado by remember(item.id){mutableStateOf(item.estado)}; var estadoMenuExpanded by remember{mutableStateOf(false)}; var hora by remember(item.id){mutableStateOf(item.hora?:"")}; var showPaymentChannels by remember(item.id){mutableStateOf(false)}; val scopeCompartir=rememberCoroutineScope()
    AlertDialog(onDismissRequest=onDismiss,title={Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween,modifier=Modifier.fillMaxWidth()){Text(item.nombre,modifier=Modifier.weight(1f));IconButton(onClick={showPaymentChannels=true}){Icon(Icons.Default.Print,contentDescription="Imprimir lugares de pago")};IconButton(onClick={clipboard.setText(androidx.compose.ui.text.AnnotatedString(item.nombre));android.widget.Toast.makeText(context,"Nombre copiado",android.widget.Toast.LENGTH_SHORT).show()}){Icon(Icons.Default.ContentCopy,contentDescription="Copiar nombre")}}},text={Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)){
        Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center){PortadaThumbnail(item.imagenUrl,driveHelper,size=140.dp)};ContactFieldRow("Num TT",item.numTT);ContactFieldRow("Ref 1",item.ref1);ContactFieldRow("Ref 2",item.ref2);ContactFieldRow("Ref 3",item.ref3);ContactFieldRow("Ref 4",item.ref4);if(item.requisito.isNotBlank())Text("Req: ${item.requisito}");item.fecha?.let{Text("Fecha: ${df.format(Date(it))}")};ColoniaLabel(item.ubicacion,style=MaterialTheme.typography.bodyMedium);if(!item.observaciones.isNullOrBlank()){Divider();Text("Observaciones: ${item.observaciones}")};if(!item.ubicacion.isNullOrBlank()&&item.ubicacion!="N/A")ContactActionsRow(numTT=null,ubicacion=item.ubicacion);Spacer(Modifier.height(4.dp));Divider();Spacer(Modifier.height(4.dp));
        ExposedDropdownMenuBox(expanded=estadoMenuExpanded,onExpandedChange={estadoMenuExpanded=it}){OutlinedTextField(value=estado,onValueChange={estado=it},label={Text("Status")},readOnly=true,trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded=estadoMenuExpanded)},modifier=Modifier.fillMaxWidth().menuAnchor());ExposedDropdownMenu(expanded=estadoMenuExpanded,onDismissRequest={estadoMenuExpanded=false}){DropdownMenuItem(text={Text("(Sin status)")},onClick={estado="";estadoMenuExpanded=false});ESTADOS_MATRIZ.forEach{opcion->DropdownMenuItem(text={Text(opcion)},onClick={estado=opcion;estadoMenuExpanded=false})}}}
        OutlinedTextField(value=hora,onValueChange={hora=it},label={Text("Hora")},readOnly=true,trailingIcon={IconButton(onClick={val cal=Calendar.getInstance();android.app.TimePickerDialog(context,{_,h,min->cal.set(Calendar.HOUR_OF_DAY,h);cal.set(Calendar.MINUTE,min);cal.set(Calendar.SECOND,0);hora=SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(cal.time)},cal.get(Calendar.HOUR_OF_DAY),cal.get(Calendar.MINUTE),true).show()}){Icon(Icons.Default.AccessTime,contentDescription="Elegir hora")}},modifier=Modifier.fillMaxWidth())
        OutlinedButton(onClick={scopeCompartir.launch{compartirMatrizPorWhatsApp(context,item,estado,hora)}},modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Share,contentDescription=null,tint=Color(0xFF25D366),modifier=Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("Compartir por WhatsApp")}
        var excluidoHoy by remember(item.id){mutableStateOf(AutomatizacionPrefs.excluidoHoy(context,item.id))}
        OutlinedButton(onClick={excluidoHoy=!excluidoHoy;AutomatizacionPrefs.excluirHoy(context,item.id,excluidoHoy);Toast.makeText(context,if(excluidoHoy)"No se le llamará ni se le mandará SMS automático hoy" else "Volverá a entrar a las corridas automáticas",Toast.LENGTH_SHORT).show()},modifier=Modifier.fillMaxWidth()){Text(if(excluidoHoy)"Reanudar contacto automático hoy" else "No contactar hoy (automático)")}
    }},confirmButton={Button(onClick={onGuardarEstadoYHora(item.id,estado,hora);onDismiss()}){Text("Guardar")}},dismissButton={TextButton(onClick=onDismiss){Text("Cerrar")}})
    if(showPaymentChannels)PaymentChannelsDialog(customerName=item.nombre,ubicacion=item.ubicacion,onDismiss={showPaymentChannels=false})
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyDatePickerDialog(onDateSelected:(Long)->Unit,onDismiss:()->Unit){val state=rememberDatePickerState();DatePickerDialog(onDismissRequest=onDismiss,confirmButton={TextButton(onClick={state.selectedDateMillis?.let(onDateSelected)}){Text("OK")}}){DatePicker(state=state)}}
