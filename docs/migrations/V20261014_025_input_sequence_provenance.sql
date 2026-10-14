-- Procedencia de la ponderacion (secuencia) de cada serie de entrada.
--
-- `weighting` ya guardaba t1/t2/unknown cuando salia de la deteccion del zip DICOM. Ahora tambien
-- puede venir de lo que declara quien carga un archivo por plano ('other' incluido), y las dos
-- cosas no se pueden confundir: una declaracion no es una deteccion ni una validacion del modelo.
--
-- Aditiva y sin backfill: las filas existentes quedan con procedencia vacia. Una fila vieja con
-- t1/t2 se lee como detectada (su unico origen era el zip) y una sin ponderacion sigue desconocida;
-- nada se completa ni se infiere en la base.
ALTER TABLE domain_input_resources
    ADD COLUMN IF NOT EXISTS sequence_source TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS sequence_description TEXT NOT NULL DEFAULT '';
