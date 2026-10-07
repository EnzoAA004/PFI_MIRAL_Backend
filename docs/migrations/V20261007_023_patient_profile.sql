-- Perfil aditivo del paciente: nombre, apellido y fecha de nacimiento opcionales.
-- Los pacientes existentes (solo con patient_reference) siguen siendo validos con NULL en
-- estas columnas; no se reescribe ninguna fila.
ALTER TABLE domain_patients
    ADD COLUMN IF NOT EXISTS first_name TEXT NULL,
    ADD COLUMN IF NOT EXISTS last_name TEXT NULL,
    ADD COLUMN IF NOT EXISTS date_of_birth DATE NULL;
