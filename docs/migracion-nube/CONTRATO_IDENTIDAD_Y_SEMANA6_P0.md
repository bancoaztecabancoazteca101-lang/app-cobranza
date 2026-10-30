# Contrato P0: identidad Matriz y Semana 6

Fecha: 2026-10-26
Rama de revisión: `chatgpt/sincronizacion-integridad-nube`

## Alcance y evidencia

Revisión estática de los archivos Kotlin consultados en la rama. No se modificó código de producción ni se ejecutaron compilaciones o pruebas en dispositivo.

Archivos revisados:
- `app/src/main/java/com/example/matrizapp/MatrizViewModel.kt`
- `app/src/main/java/com/example/matrizapp/Sem6ViewModel.kt`
- `app/src/main/java/com/example/matrizapp/Sem6.kt`
- `app/src/main/java/com/example/matrizapp/Sem6Generador.kt` (referencias y fragmentos localizados)
- `app/src/main/java/com/example/matrizapp/Entities.kt`
- `app/src/main/java/com/example/matrizapp/DAOs.kt`
- `app/src/main/java/com/example/matrizapp/ContactoExtra.kt`
- `app/src/main/java/com/example/matrizapp/PaseCarteraViewModel.kt`

## 1. Identidad de Matriz

### Hallazgos
- `MatrizViewModel.guardarGestion()` y `guardarRegistroCompleto()` actualizan Room y luego llaman a sincronización.
- `cambiarIdYGuardar()` valida colisión del nuevo ID en Room y Sheets; intenta renombrar la fila remota antes de ejecutar `matrizDao.renameId()`. Para un alta aún no enviada, permite renombrar localmente.
- Los formularios de Matriz y Ruta IA usan esta operación; Filtrar tiene una ruta de guardado completo propia.
- Las visitas de mapa usan claves construidas como `M:<id>`; Semana 6 usa el ID como parte de su clave primaria `(hoja,id)`.
- Contactos extra identifican al cliente titular mediante `clienteId`.
- Pase tiene identidad propia y un `origenMatrizId`; el comentario de la entidad indica que la copia de Pase es independiente y que no sincroniza cambios de vuelta a Matriz. No asumir que cambiar el ID de Matriz debe renombrar el ID independiente de Pase.
- El método de cambio de ID de Pase es una operación distinta, por lo que no deben confundirse ambos flujos.

### Regla de diseño
Antes de migrar, documentar para cada referencia si debe seguir al cliente cuando cambia el ID funcional. Evaluar un ID técnico global e inmutable separado del ID editable de Sheets. Mantener el ID funcional actual durante la transición de Sheets. No cambiar automáticamente IDs de Pase ni historial de visitas sin una regla explícita.

## 2. Semana 6: proyección con campos editables

### Hallazgos
- `Sem6ViewModel` lee registros de `Sem6Dao.observarHoja()` y observa cambios en Matriz para ejecutar `Sem6Generador.actualizar()`.
- La importación inicial de las hojas `Cont-Sem-NN` se controla mediante `SharedPreferences` (`sem6_interna/importado_v1`).
- `guardarNotas()` edita en Room `seContiene`, `susceptible`, `observaciones`, `capital` y `abono`.
- `eliminarRegistro()` elimina de Room y registra el ID eliminado por hoja en `Sem6CacheStore`.
- `Sem6CacheStore` usa preferencias locales. Su marca de eliminado no constituye un borrado compartido entre teléfonos.
- `Sem6RegistroEntity` usa clave primaria compuesta `(hoja,id)`; la fila combina datos base del cliente y datos de seguimiento.

### Contrato provisional por campo
1. Datos base que provienen de Matriz: la nube debe mantener su origen canónico y refrescar la proyección sin destruir los campos de seguimiento.
2. Campos editables desde Semana 6 (`seContiene`, `susceptible`, `observaciones`, `capital`, `abono`): deben persistir como datos compartidos versionados, sin ser sobrescritos por la regeneración de datos base.
3. Campos calculados (`visitas`, `ultimaFechaVisita`, `colonia` y otros): falta completar el examen de `Sem6Generador.actualizar()` para fijar su fuente y política.
4. Borrado manual: reemplazar la preferencia local por una exclusión/tombstone compartida, con alcance por hoja y una regla definida para cuándo puede volver a aparecer el cliente.

## 3. Dependencias y riesgos de identidad

| Área | Evidencia actual | Regla antes de migrar |
|---|---|---|
| Matriz | ID funcional editable; validación en Room y Sheets | Resolver renombre y colisiones entre dispositivos |
| Visita de mapa | Clave `M:<id>` y fecha; historial local | Decidir si se conserva la identidad histórica o se reasocia |
| Semana 6 | Clave `(hoja,id)` | Cambiar referencias sin perder campos propios ni tombstones |
| Contacto extra | `clienteId` identifica al titular | Decidir si el vínculo sigue al cliente tras renombrar ID |
| Pase | ID independiente y `origenMatrizId` | Preservar autonomía; definir solo el vínculo de origen |

## 4. Próximos pasos bloqueantes

1. Leer completo `Sem6Generador.actualizar()` y contrastarlo con todos los campos de `Sem6RegistroEntity`.
2. Revisar `MatrizDao.renameId()` y los DAOs de visitas/contactos para confirmar si existe cascada o si quedan referencias con el ID anterior.
3. Especificar tombstones, colisiones, versiones y comportamiento sin conexión.
4. Elegir un piloto sencillo solo después de comprobar sus rutas reales de alta, edición y borrado.
5. Mantener Firestore con reglas `deny-all`; no añadir dependencias, cambiar `DB_VERSION` ni modificar código de producción en esta fase.

## Estado
Documento de diseño basado en inspección estática. No demuestra que la sincronización global esté implementada ni validada. `main` no se modifica y el PR existente no debe fusionarse sin autorización del usuario.
