import numpy as np
import matplotlib.pyplot as plt
from matplotlib.animation import FuncAnimation, PillowWriter
import os


# =========================
# Engine parameters
# =========================

class RocketEngine:

    def __init__(
        self,
        thrust=100000,
        nozzle_diameter=0.8,
        chamber_pressure=7e6,
        chamber_temperature=3500,
        gamma=1.22,
        R=355
    ):

        self.F = thrust
        self.D = nozzle_diameter
        self.Pc = chamber_pressure
        self.Tc = chamber_temperature
        self.gamma = gamma
        self.R = R

        self.area = np.pi*(self.D/2)**2


# =========================
# Plume physics
# =========================

def calculate_plume(engine, ambient_pressure):

    if ambient_pressure < 10:
        pressure_ratio = 0.001
    else:
        pressure_ratio = ambient_pressure/engine.Pc


    Ve = np.sqrt(
        2*engine.gamma/(engine.gamma-1)
        *
        engine.R
        *
        engine.Tc
        *
        (
        1-pressure_ratio**(
        (engine.gamma-1)/engine.gamma)
        )
    )


    # expansion factor
    expansion = np.sqrt(
        engine.Pc/max(ambient_pressure,100)
    )


    length = engine.D*8*expansion**0.25

    radius = engine.D*0.5*expansion**0.2


    return length,radius,Ve



# =========================
# plume field
# =========================

def generate_field(
        engine,
        ambient_pressure,
        resolution=400):


    L,R,V=calculate_plume(
        engine,
        ambient_pressure
    )


    margin=L*0.25


    x=np.linspace(
        -margin,
        L+margin,
        resolution
    )

    y=np.linspace(
        -R*3,
        R*3,
        resolution//2
    )


    X,Y=np.meshgrid(x,y)


    # axial decay

    envelope=np.exp(
        -X/(L*0.8)
    )


    # radial spreading

    width=R*(1+X/L*2)


    radial=np.exp(
        -(Y/width)**2
    )


    plume=envelope*radial



    # shock diamonds

    shock=np.sin(
        X/(engine.D*0.45)
        *
        np.pi
    )**2


    plume*=(
        0.75+
        0.25*shock
    )


    # remove nozzle upstream

    plume[X<0]=0


    # adaptive threshold

    threshold=np.max(plume)*0.015

    plume[plume<threshold]=0


    return X,Y,plume,L,R



# =========================
# render gif
# =========================


def render_case(
        name,
        engine,
        pressure):


    X,Y,Z,L,R=generate_field(
        engine,
        pressure
    )


    fig,ax=plt.subplots(
        figsize=(8,3),
        dpi=120
    )


    ax.axis("off")


    img=ax.imshow(
        Z,
        cmap="inferno",
        origin="lower",
        extent=[
            X.min(),
            X.max(),
            Y.min(),
            Y.max()
        ],
        alpha=0.9
    )


    ax.set_xlim(
        X.min(),
        X.max()
    )

    ax.set_ylim(
        Y.min(),
        Y.max()
    )



    frames=[]

    for i in range(30):

        phase=i/30*np.pi*2

        shift=np.sin(
            X*20+phase
        )*0.03

        frame=Z*(1+shift)

        frames.append(frame)


    def update(i):

        img.set_array(
            frames[i]
        )

        return img,


    ani=FuncAnimation(
        fig,
        update,
        frames=len(frames),
        interval=50
    )


    os.makedirs(
        "plume_outputs",
        exist_ok=True
    )


    ani.save(
        f"plume_outputs/{name}.gif",
        writer=PillowWriter(
            fps=20
        )
    )


    plt.close()



# =========================
# main
# =========================


if __name__=="__main__":


    engine=RocketEngine(
        thrust=500000,
        nozzle_diameter=1.2
    )


    cases={

        "sea_level":
            101325,

        "high_altitude":
            5000,

        "vacuum":
            0

    }


    for name,p in cases.items():

        render_case(
            name,
            engine,
            p
        )


    print(
        "Finished"
    )