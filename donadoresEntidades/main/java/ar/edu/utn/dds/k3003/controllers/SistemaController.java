package ar.edu.utn.dds.k3003.controllers;

import ar.edu.utn.dds.k3003.Fachada;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sistema")
public class SistemaController {

    private final Fachada fachada;

    public SistemaController(Fachada fachada) {
        this.fachada = fachada;
    }

    @DeleteMapping("/base-de-datos")
    public ResponseEntity<String> limpiarBaseDeDatos() {
        fachada.limpiarDatos();
        return ResponseEntity.ok("Base de datos limpia");
    }

    @GetMapping("/donadores")
    public ResponseEntity<?> obtenerTodosLosDonadores() {
        return ResponseEntity.ok(fachada.listarDonadores());
    }

    @GetMapping("/entidades")
    public ResponseEntity<?> obtenerTodasLasEntidades() {
        return ResponseEntity.ok(fachada.listarEntidades());
    }
}
